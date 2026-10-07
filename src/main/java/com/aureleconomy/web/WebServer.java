package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.webstore.PurchaseQueue;
import com.aureleconomy.webstore.WebSnapshotCache;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server: serves the dashboard frontend and routes API calls to
 * {@link DashboardApiHandler}.
 */
public class WebServer {

    private final AurelEconomy plugin;
    private final WebSessionManager sessionManager;
    private final WebSnapshotCache cache;
    private final PurchaseQueue queue;
    private final WebPurchaseExecutor executor;
    private final String serverId;
    private HttpServer server;
    private final int port;
    private final String host;
    /**
     * The pool handed to {@link HttpServer}.
     *
     * <p>{@code newFixedThreadPool} makes non-daemon threads, so the pool has to be
     * shut down explicitly in {@link #stop()} or they outlive plugin disable and
     * stall a reload.
     */
    private ExecutorService httpExecutor;
    private boolean started = false;

    private static final Map<String, String> MIME_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "png", "image/png",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8");

    public WebServer(AurelEconomy plugin, WebSnapshotCache cache, PurchaseQueue queue,
                     WebPurchaseExecutor executor, String serverId) {
        this.plugin = plugin;
        this.port = plugin.getConfig().getInt("web.local.port", 8585);
        this.host = plugin.getConfig().getString("web.local.host", "localhost");
        this.serverId = serverId;
        this.cache = cache;
    this.queue = queue;
        this.executor = executor;
        int minutes = plugin.getConfig().getInt("web.local.session-timeout-minutes", 60);
        this.sessionManager = new WebSessionManager(plugin, minutes);
    }

    public boolean start() {
        try {
            // Honour web.local.host. It used to be ignored and the socket bound to
            // 0.0.0.0, so opting into local mode exposed the dashboard - and the
            // session token in its URL, in cleartext over plain HTTP - on every
            // interface of what is usually a publicly reachable Minecraft host.
            // The config default is "localhost", so the safe behaviour is also the
            // documented one; anyone who genuinely wants remote access can set
            // web.local.host themselves.
            String bindHost = (host == null || host.isBlank()) ? "localhost" : host.trim();
            server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
            httpExecutor = Executors.newFixedThreadPool(4);
            server.setExecutor(httpExecutor);

            if ("0.0.0.0".equals(bindHost) || "::".equals(bindHost)) {
                plugin.getComponentLogger().warn(
                        "Web dashboard is bound to " + bindHost + ", so it is reachable from "
                                + "every network interface. It is served over plain HTTP, so session "
                                + "tokens travel in cleartext; put it behind a proxy with TLS or set "
                                + "web.local.host to localhost.");
            }

            server.createContext("/api/",
                    new DashboardApiHandler(plugin, sessionManager, cache, queue, serverId));

            // The dashboard frontend resolves its server id from the URL path
            // (/shop/<serverId>), so that is where index.html is served.
            server.createContext("/shop", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/shop") || path.equals("/shop/") || path.isEmpty()) {
                    redirect(exchange, "/shop/" + serverId);
                    return;
                }
                String[] parts = path.split("/");
                String requested = parts.length >= 3 ? parts[2] : serverId;
                if (!serverId.equals(requested)) {
                    byte[] nf = "<!DOCTYPE html><html><body><h1>404 Not Found</h1></body></html>"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    applySecurityHeaders(exchange);
                    exchange.sendResponseHeaders(404, nf.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(nf);
                    }
                    return;
                }
                serveStaticFile(exchange, "/index.html");
            });

            // The frontend references its assets as /static/app.js and /static/style.css.
            server.createContext("/static", exchange -> {
                String path = exchange.getRequestURI().getPath();
                serveStaticFile(exchange, path.startsWith("/static") ? path.substring(7) : path);
            });

            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") || path.isEmpty()) {
                    redirect(exchange, "/shop/" + serverId);
                    return;
                }
                serveStaticFile(exchange, path);
            });

            server.start();
            started = true;
            executor.start();
            plugin.getComponentLogger().info("Web dashboard started on port " + port
                    + " (server id '" + serverId + "', queue: " + queue.getClass().getSimpleName() + ")");
            plugin.getComponentLogger().info("Open http://" + bindHost + ":" + port
                    + "/shop/" + serverId + " in your browser");

            return true;
        } catch (Exception e) {
            started = false;
            plugin.getComponentLogger().error("Failed to start web server on port " + port
                    + " — is the port already in use?", e);
            return false;
        }
    }

    public void stop() {
        if (executor != null) {
            executor.stop();
        }
        if (server != null) {
            server.stop(0);
            started = false;
            plugin.getComponentLogger().info("Web dashboard stopped.");
        }
        // Shut the pool down explicitly: its threads are non-daemon, so leaving
        // them running keeps the JVM alive and the port can stay bound across a
        // plugin reload.
        if (httpExecutor != null) {
            httpExecutor.shutdownNow();
            httpExecutor = null;
        }
        // Shutdown session cleanup task and clear sessions
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
    }

    public String getServerId() {
        return serverId;
    }

    public boolean isRunning() {
        return started;
    }

    public WebSessionManager getSessionManager() {
        return sessionManager;
    }

    public int getPort() {
        return port;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /**
     * Content-Security-Policy for the dashboard.
     *
     * <p>Same policy the cloud backend serves. The markup binds its handlers with
     * data- attributes and keeps its one inline style in style.css, so script-src
     * and style-src need nothing beyond 'self' - no 'unsafe-inline' and no hashes
     * to keep in step with the markup. Google Fonts and the Minecraft item-texture
     * hosts are the only external origins the page uses.
     *
     * <p>frame-ancestors is what stops a hostile page from iframing the dashboard
     * and clickjacking the Buy/Sell buttons; without it the local server served no
     * framing restriction at all.
     */
    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' https://fonts.googleapis.com",
            "font-src 'self' https://fonts.gstatic.com",
            "img-src 'self' https://assets.mcasset.cloud data: https://mc-heads.net",
            "connect-src 'self'",
            "frame-ancestors 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "object-src 'none'");

    /**
     * Apply the headers the static dashboard shell should carry.
     *
     * <p>HSTS is deliberately absent: this server speaks plain HTTP, and an HSTS
     * header on a host:port that is never HTTPS would only confuse a browser. The
     * framing and MIME-sniffing restrictions are still worth having.
     *
     * <p>{@code no-cache} rather than {@code no-store} because these are the
     * shell assets, not player data. The JSON API sets {@code no-store} itself,
     * since that is where balances live.
     */
    private static void applySecurityHeaders(HttpExchange exchange) {
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Security-Policy", CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "SAMEORIGIN");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-cache");
    }

    /** Serve a file from src/main/resources/web/ inside the JAR. */
    private void serveStaticFile(HttpExchange exchange, String path) throws IOException {
        String sanitized = path.replace("..", "").replaceAll("[^a-zA-Z0-9/._-]", "");
        String resourcePath = "web" + sanitized;

        try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                String notFound = "<!DOCTYPE html><html><body><h1>404 Not Found</h1></body></html>";
                byte[] bytes = notFound.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                applySecurityHeaders(exchange);
                exchange.sendResponseHeaders(404, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }

            byte[] data = is.readAllBytes();

            String ext = sanitized.contains(".") ? sanitized.substring(sanitized.lastIndexOf('.') + 1) : "html";
            String contentType = MIME_TYPES.getOrDefault(ext, "application/octet-stream");

            exchange.getResponseHeaders().set("Content-Type", contentType);
            applySecurityHeaders(exchange);
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }
}

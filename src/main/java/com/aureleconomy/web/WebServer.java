package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.webstore.PurchaseQueue;
import com.aureleconomy.webstore.WebSnapshotCache;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        this.serverId = serverId;
        this.cache = cache;
    this.queue = queue;
        this.executor = executor;
        int minutes = plugin.getConfig().getInt("web.local.session-timeout-minutes", 60);
        this.sessionManager = new WebSessionManager(plugin, minutes);
    }

    public boolean start() {
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));

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
            plugin.getComponentLogger().info("Open http://localhost:" + port
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

    /** Serve a file from src/main/resources/web/ inside the JAR. */
    private void serveStaticFile(HttpExchange exchange, String path) throws IOException {
        String sanitized = path.replace("..", "").replaceAll("[^a-zA-Z0-9/._-]", "");
        String resourcePath = "web" + sanitized;

        try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                String notFound = "<!DOCTYPE html><html><body><h1>404 Not Found</h1></body></html>";
                byte[] bytes = notFound.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
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
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }
}

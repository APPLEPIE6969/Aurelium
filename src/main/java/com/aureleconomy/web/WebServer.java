package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
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
 * Embedded HTTP server that serves the web dashboard frontend
 * and routes API calls to ApiHandler.
 */
public class WebServer {

    private final AurelEconomy plugin;
    private final WebSessionManager sessionManager;
    private HttpServer server;
    private final int port;
    private boolean started = false;

    // Content-type mappings
    private static final Map<String, String> MIME_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "png", "image/png",
            "svg", "image/svg+xml",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8");

    public WebServer(AurelEconomy plugin) {
        this.plugin = plugin;
        this.port = plugin.getConfig().getInt("web.local.port", 8585);
        this.sessionManager = new WebSessionManager(60); // Hardcoded 60 minutes
    }

    public boolean start() {
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));

            // API routes
            ApiHandler apiHandler = new ApiHandler(plugin, sessionManager);
            server.createContext("/api/", apiHandler);

            // Static file serving (frontend)
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();

                // Default to index.html
                if (path.equals("/") || path.isEmpty()) {
                    path = "/index.html";
                }

                serveStaticFile(exchange, path);
            });

            server.start();
            started = true;
            plugin.getComponentLogger().info("Web dashboard started successfully on port " + port
                    + " — open http://localhost:" + port + " in your browser");

            // Schedule session cleanup every 60 seconds
            plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin,
                    sessionManager::cleanup, 1200L, 1200L);

            return true;
        } catch (Exception e) {
            started = false;
            plugin.getComponentLogger().error("Failed to start web server on port " + port
                    + " — is the port already in use?", e);
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            started = false;
            plugin.getComponentLogger().info("Web dashboard stopped.");
        }
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

    /** Serve a file from src/main/resources/web/ inside the JAR. */
    private void serveStaticFile(HttpExchange exchange, String path) throws IOException {
        // Sanitize path to prevent directory traversal
        String sanitized = path.replace("..", "").replaceAll("[^a-zA-Z0-9/._-]", "");
        String resourcePath = "web" + sanitized;

        try (InputStream is = plugin.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                // 404
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

            // Determine content type from extension
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

package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.webstore.PurchaseQueue;
import com.aureleconomy.webstore.WebSnapshot;
import com.aureleconomy.webstore.WebSnapshotCache;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Serves the WebMarketMC dashboard API from a {@link WebSnapshotCache} and
 *
 * and a {@link PurchaseQueue} (writes).
 *
 * <p>Route shape matches the standalone dashboard backend so the same
 * {@code public/} frontend works against either:
 *
 * <pre>
 *   GET  /api/{serverId}/player          GET  /api/{serverId}/orders
 *   GET  /api/{serverId}/categories      GET  /api/{serverId}/stocks
 *   GET  /api/{serverId}/items           GET  /api/{serverId}/price-history
 *   GET  /api/{serverId}/search          GET  /api/{serverId}/purchase-status
 *   GET  /api/{serverId}/auctions
 *   POST /api/{serverId}/buy             POST /api/{serverId}/bid
 *   POST /api/{serverId}/fill-order
 * </pre>
 *
 * <p>Auth is {@code Authorization: Bearer <token>} from {@code /web}. The legacy
 * {@code ?token=} query parameter is still accepted so older links keep working.
 *
 * <p>Mutating routes only <em>queue</em> work; the plugin drains the queue on the
 * main thread, which keeps Bukkit API calls off the HTTP worker threads.
 */
public class DashboardApiHandler implements HttpHandler {

    private static final int ITEMS_PER_PAGE = 28;
    private static final int MAX_AMOUNT = 64;

    private final AurelEconomy plugin;
    private final WebSessionManager sessions;
    private final WebSnapshotCache cache;
    private final PurchaseQueue queue;
    private final String serverId;

    public DashboardApiHandler(AurelEconomy plugin, WebSessionManager sessions,
                               WebSnapshotCache cache, PurchaseQueue queue, String serverId) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.cache = cache;
    this.queue = queue;
        this.serverId = serverId;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        // /api/{serverId}/{action}
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        if (parts.length < 4) {
            send(exchange, 404, "{\"error\":\"Not found\"}");
            return;
        }
        String requestedServer = parts[2];
        String action = parts[3];

        if (!serverId.equals(requestedServer)) {
            send(exchange, 404, "{\"error\":\"Unknown server\"}");
            return;
        }

        UUID player = authenticate(exchange, params);
        if (player == null) {
            send(exchange, 401, "{\"error\":\"Invalid or expired session. Use /web in-game.\"}");
            return;
        }

        try {
            route(exchange, action, params, player);
        } catch (Exception e) {
            plugin.getComponentLogger().error("Dashboard API error on /" + action, e);
            send(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private UUID authenticate(HttpExchange exchange, Map<String, String> params) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            UUID uuid = sessions.validate(header.substring(7).trim());
            if (uuid != null) {
                return uuid;
            }
        }
        return sessions.validate(params.get("token"));
    }

    private void route(HttpExchange exchange, String action, Map<String, String> params, UUID player)
            throws IOException {
        boolean post = "POST".equalsIgnoreCase(exchange.getRequestMethod());

        switch (action) {
            case "player" -> send(exchange, 200, playerJson(player));
            case "categories" -> send(exchange, 200, cache.categoriesJson());
            case "items" -> send(exchange, 200, pagedItems(params.get("category"), params, false));
            case "search" -> {
                String q = params.get("q");
                if (q == null || q.isBlank()) {
                    send(exchange, 400, "{\"error\":\"Missing search query\"}");
                } else {
                    send(exchange, 200, searchItems(q, params));
                }
            }
            case "auctions" -> send(exchange, 200, cache.auctionsJson());
            case "orders" -> send(exchange, 200, cache.ordersJson());
            case "stocks" -> send(exchange, 200, cache.stocksJson());
            case "price-history" -> send(exchange, 200, cache.priceHistoryJson());
            case "purchase-status" -> purchaseStatus(exchange, params, player);
            case "buy" -> {
                if (post) {
                    enqueue(exchange, PurchaseQueue.Type.BUY, player);
                } else {
                    send(exchange, 405, "{\"error\":\"POST required\"}");
                }
            }
            case "bid" -> {
                if (post) {
                    enqueue(exchange, PurchaseQueue.Type.BID, player);
                } else {
                    send(exchange, 405, "{\"error\":\"POST required\"}");
                }
            }
            case "fill-order" -> {
                if (post) {
                    enqueue(exchange, PurchaseQueue.Type.FILL_ORDER, player);
                } else {
                    send(exchange, 405, "{\"error\":\"POST required\"}");
                }
            }
            default -> send(exchange, 404, "{\"error\":\"Not found\"}");
        }
    }

    // ── GET payloads ──────────────────────────────────────────────────

    private String playerJson(UUID uuid) {
        String defaultCurrency = plugin.getEconomyManager().getDefaultCurrency();
        var offline = org.bukkit.Bukkit.getOfflinePlayer(uuid);
        String name = offline.getName() != null ? offline.getName() : uuid.toString();

        StringBuilder json = new StringBuilder();
        json.append("{\"name\":\"").append(WebSnapshot.esc(name)).append("\"");
        json.append(",\"uuid\":\"").append(uuid).append("\"");
        json.append(",\"defaultCurrency\":\"").append(WebSnapshot.esc(defaultCurrency)).append("\"");
        json.append(",\"balances\":{");
        int i = 0;
        json.append("\"").append(WebSnapshot.esc(defaultCurrency)).append("\":")
                .append(plugin.getEconomyManager().getBalance(offline, defaultCurrency));
        var section = plugin.getConfig().getConfigurationSection("economy.currencies");
        if (section != null) {
            for (String currency : section.getKeys(false)) {
                if (currency.equals(defaultCurrency)) {
                    continue;
                }
                if (i++ > 0) {
                    json.append(",");
                }
                json.append("\"").append(WebSnapshot.esc(currency)).append("\":")
                        .append(plugin.getEconomyManager().getBalance(offline, currency));
            }
        }
        return json.append("}}").toString();
    }

    /** {@code itemsJson} is a map of category id -> array; slice one array. */
    private String pagedItems(String category, Map<String, String> params, boolean search) {
        JsonObject all = parseObject(cache.itemsJson());
        JsonArray source;
        if (all != null && category != null && all.has(category)) {
            source = all.getAsJsonArray(category);
        } else if (all != null && all.entrySet().isEmpty()) {
            source = new JsonArray();
        } else {
            source = all == null ? new JsonArray() : flattenItems(all);
        }
        return page(source, params);
    }

    private JsonArray flattenItems(JsonObject byCategory) {
        JsonArray out = new JsonArray();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (var entry : byCategory.entrySet()) {
            for (JsonElement el : entry.getValue().getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                String key = o.has("key") ? o.get("key").getAsString()
                        : (o.has("name") ? o.get("name").getAsString() : null);
                if (key == null || seen.add(key)) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    private String searchItems(String query, Map<String, String> params) {
        String needle = query.toLowerCase();
        JsonArray all = flattenItems(parseObject(cache.itemsJson()));
        JsonArray hits = new JsonArray();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (JsonElement el : all) {
            JsonObject o = el.getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "";
            String key = o.has("key") ? o.get("key").getAsString() : name;
            if (name.toLowerCase().contains(needle) && seen.add(key)) {
                hits.add(el);
            }
        }
        return page(hits, params);
    }

    private String page(JsonArray source, Map<String, String> params) {
        int pageNo = parseInt(params.get("page"), 0);
        if (pageNo < 0) {
            pageNo = 0;
        }
        int total = source.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) ITEMS_PER_PAGE));
        int start = pageNo * ITEMS_PER_PAGE;
        JsonArray slice = new JsonArray();
        for (int i = start; i < Math.min(start + ITEMS_PER_PAGE, total); i++) {
            slice.add(source.get(i));
        }
        JsonObject out = new JsonObject();
        out.addProperty("page", pageNo);
        out.addProperty("totalPages", totalPages);
        out.addProperty("totalItems", total);
        out.add("items", slice);
        return out.toString();
    }

    private void purchaseStatus(HttpExchange exchange, Map<String, String> params, UUID player)
            throws IOException {
        String id = params.get("id");
        PurchaseQueue.Purchase purchase = id == null ? null : queue.find(id);
        if (purchase == null) {
            send(exchange, 404, "{\"error\":\"Purchase not found\"}");
            return;
        }
        if (!purchase.player().equals(player)) {
            send(exchange, 403, "{\"error\":\"Not your purchase\"}");
            return;
        }
        JsonObject out = new JsonObject();
        out.addProperty("status", purchase.status());
        if (purchase.resultJson() == null) {
            out.add("result", com.google.gson.JsonNull.INSTANCE);
        } else {
            out.add("result", JsonParser.parseString(purchase.resultJson()));
        }
        send(exchange, 200, out.toString());
    }

    // ── POST: queue work for the main thread ──────────────────────────

    private void enqueue(HttpExchange exchange, PurchaseQueue.Type type, UUID player) throws IOException {
        JsonObject body = readJson(exchange);
        if (body == null) {
            send(exchange, 400, "{\"error\":\"Invalid request body\"}");
            return;
        }

        String itemKey = optString(body, "item");
        int amount = (int) optDouble(body, "amount", Double.NaN);
        int auctionId = (int) optDouble(body, "auctionId", Double.NaN);
        int orderId = (int) optDouble(body, "orderId", Double.NaN);

        String message;
        switch (type) {
            case BUY -> {
                if (itemKey == null || !isWholeInRange(amount)) {
                    send(exchange, 400, "{\"error\":\"Invalid item or amount\"}");
                    return;
                }
                message = "Purchase queued - delivering in-game...";
            }
            case BID -> {
                double bidAmount = optDouble(body, "amount", Double.NaN);
                if (!(auctionId > 0) || !Double.isFinite(bidAmount) || bidAmount <= 0) {
                    send(exchange, 400, "{\"error\":\"Invalid auction or amount\"}");
                    return;
                }
                // Fractional bids are meaningless for the in-game BidGUI.
                amount = (int) bidAmount;
                message = "Bid queued - confirming in-game...";
            }
            case FILL_ORDER -> {
                if (!(orderId > 0) || !isWholeInRange(amount)) {
                    send(exchange, 400, "{\"error\":\"Invalid order or amount\"}");
                    return;
                }
                message = "Fulfillment queued - verifying in-game inventory...";
            }
            default -> {
                send(exchange, 400, "{\"error\":\"Unsupported action\"}");
                return;
            }
        }

        String purchaseId = queue.enqueue(type, player, itemKey, auctionId, orderId, amount);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("purchaseId", purchaseId);
        out.addProperty("message", message);
        send(exchange, 200, out.toString());
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static boolean isWholeInRange(double v) {
        return Double.isFinite(v) && v == Math.floor(v) && v >= 1 && v <= MAX_AMOUNT;
    }

    private static String optString(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static double optDouble(JsonObject o, String key, double fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(key).getAsDouble();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private JsonObject readJson(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.isBlank()) {
                return null;
            }
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject parseObject(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                continue;
            }
            out.put(urlDecode(pair.substring(0, eq)), urlDecode(pair.substring(eq + 1)));
        }
        return out;
    }

    private static String urlDecode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Formats a money amount the way the dashboard displays it. */
    static String formatAmount(BigDecimal amount) {
        return amount.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }
}

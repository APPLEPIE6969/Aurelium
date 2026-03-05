package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Handles REST API requests from the web dashboard.
 * All endpoints require a valid session token as a query parameter
 * (?token=...).
 */
public class ApiHandler implements HttpHandler {

    private final AurelEconomy plugin;
    private final WebSessionManager sessions;

    public ApiHandler(AurelEconomy plugin, WebSessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS headers
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = exchange.getRequestURI().getPath();
        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());

        // Validate session
        UUID playerUuid = sessions.validate(params.get("token"));
        if (playerUuid == null) {
            sendJson(exchange, 401, "{\"error\":\"Invalid or expired session. Use /web in-game.\"}");
            return;
        }

        try {
            switch (path) {
                case "/api/player" -> handlePlayer(exchange, playerUuid);
                case "/api/categories" -> handleCategories(exchange);
                case "/api/items" -> handleItems(exchange, params);
                case "/api/search" -> handleSearch(exchange, params);
                case "/api/buy" -> handleBuy(exchange, playerUuid, params);
                default -> sendJson(exchange, 404, "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            plugin.getComponentLogger().error("Web API error", e);
            sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    // ── GET /api/player ──────────────────────────────────────────────

    private void handlePlayer(HttpExchange exchange, UUID uuid) throws IOException {
        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        String name = player.getName() != null ? player.getName() : uuid.toString();

        // Build balances JSON for all configured currencies
        String defaultCurrency = plugin.getEconomyManager().getDefaultCurrency();
        Map<String, Object> currencies = new LinkedHashMap<>();

        // Default currency balance
        double defaultBal = plugin.getEconomyManager().getBalance(player, defaultCurrency);
        currencies.put(defaultCurrency, defaultBal);

        // Additional currencies from config
        if (plugin.getConfig().isConfigurationSection("economy.currencies")) {
            for (String currencyName : plugin.getConfig().getConfigurationSection("economy.currencies")
                    .getKeys(false)) {
                if (!currencyName.equals(defaultCurrency)) {
                    double bal = plugin.getEconomyManager().getBalance(player, currencyName);
                    currencies.put(currencyName, bal);
                }
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\"name\":").append(jsonStr(name));
        json.append(",\"uuid\":").append(jsonStr(uuid.toString()));
        json.append(",\"defaultCurrency\":").append(jsonStr(defaultCurrency));
        json.append(",\"balances\":{");
        int i = 0;
        for (var entry : currencies.entrySet()) {
            if (i++ > 0)
                json.append(",");
            json.append(jsonStr(entry.getKey())).append(":").append(entry.getValue());
        }
        json.append("}}");

        sendJson(exchange, 200, json.toString());
    }

    // ── GET /api/categories ──────────────────────────────────────────

    private void handleCategories(HttpExchange exchange) throws IOException {
        StringBuilder json = new StringBuilder("[");
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            Category cat = cats[i];
            if (i > 0)
                json.append(",");
            json.append("{\"id\":").append(jsonStr(cat.name()));
            json.append(",\"name\":").append(jsonStr(cat.name));
            json.append(",\"icon\":").append(jsonStr(cat.icon.name().toLowerCase()));
            json.append(",\"itemCount\":").append(MarketItems.getItems(cat).size());
            json.append("}");
        }
        json.append("]");
        sendJson(exchange, 200, json.toString());
    }

    // ── GET /api/items?category=X&page=0 ─────────────────────────────

    private void handleItems(HttpExchange exchange, Map<String, String> params) throws IOException {
        String catName = params.getOrDefault("category", "");
        int page = parseIntParam(params, "page", 0);
        int perPage = 28;

        Category cat;
        try {
            cat = Category.valueOf(catName);
        } catch (IllegalArgumentException e) {
            sendJson(exchange, 400, "{\"error\":\"Invalid category\"}");
            return;
        }

        List<MarketEntry> all = MarketItems.getItems(cat).stream()
                .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                .toList();

        int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / perPage));
        int start = page * perPage;
        int end = Math.min(start + perPage, all.size());

        List<MarketEntry> pageItems = (start < all.size()) ? all.subList(start, end) : List.of();

        sendJson(exchange, 200, buildItemsJson(pageItems, page, totalPages, all.size()));
    }

    // ── GET /api/search?q=X&page=0 ───────────────────────────────────

    private void handleSearch(HttpExchange exchange, Map<String, String> params) throws IOException {
        String query = params.getOrDefault("q", "").toLowerCase();
        int page = parseIntParam(params, "page", 0);
        int perPage = 28;

        if (query.isEmpty()) {
            sendJson(exchange, 400, "{\"error\":\"Missing search query\"}");
            return;
        }

        List<MarketEntry> results = new ArrayList<>();
        for (Category cat : Category.values()) {
            if (cat == Category.ALL_ITEMS)
                continue;
            for (MarketEntry entry : MarketItems.getItems(cat)) {
                if (plugin.getMarketManager().isBlacklisted(entry.material))
                    continue;
                String name = (entry.customName != null ? entry.customName : entry.material.name()).toLowerCase();
                if (name.contains(query)) {
                    results.add(entry);
                }
            }
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) results.size() / perPage));
        int start = page * perPage;
        int end = Math.min(start + perPage, results.size());
        List<MarketEntry> pageItems = (start < results.size()) ? results.subList(start, end) : List.of();

        sendJson(exchange, 200, buildItemsJson(pageItems, page, totalPages, results.size()));
    }

    // ── POST /api/buy?item=DIAMOND&amount=1 ──────────────────────────

    private void handleBuy(HttpExchange exchange, UUID playerUuid, Map<String, String> params) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Use POST\"}");
            return;
        }

        String itemKey = params.getOrDefault("item", "");
        int amount = parseIntParam(params, "amount", 1);
        if (amount < 1 || amount > 64)
            amount = 1;

        // Resolve the item
        double buyPrice;
        String currency;
        Material material;

        try {
            material = Material.valueOf(itemKey.toUpperCase());
            buyPrice = plugin.getMarketManager().getBuyPrice(material);
            currency = plugin.getMarketManager().getCurrency(material);
        } catch (IllegalArgumentException e) {
            // Try as a custom name (e.g. spawner)
            buyPrice = plugin.getMarketManager().getBuyPrice(itemKey);
            currency = plugin.getMarketManager().getCurrency(itemKey);
            material = Material.SPAWNER; // Custom named items are typically spawners
        }

        if (buyPrice <= 0) {
            sendJson(exchange, 400, "{\"error\":\"Item not for sale\"}");
            return;
        }

        double totalCost = buyPrice * amount;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);

        if (!plugin.getEconomyManager().has(offlinePlayer, totalCost, currency)) {
            String formatted = plugin.getEconomyManager().format(totalCost, currency);
            sendJson(exchange, 400, "{\"error\":\"Not enough funds. You need " + escapeJson(formatted) + "\"}");
            return;
        }

        // Execute on main thread
        final double finalCost = totalCost;
        final int finalAmount = amount;
        final String finalCurrency = currency;
        final Material finalMaterial = material;
        final String finalItemKey = itemKey;

        CompletableFuture<String> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player onlinePlayer = Bukkit.getPlayer(playerUuid);
            if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                future.complete("{\"error\":\"You must be online to buy items\"}");
                return;
            }

            // Check inventory space
            ItemStack toGive = new ItemStack(finalMaterial, finalAmount);
            if (!com.aureleconomy.utils.InventoryUtils.hasSpace(onlinePlayer.getInventory(), toGive, finalAmount)) {
                future.complete("{\"error\":\"Your inventory is full\"}");
                return;
            }

            plugin.getEconomyManager().withdraw(onlinePlayer, finalCost, finalCurrency);
            onlinePlayer.getInventory().addItem(toGive);
            plugin.getMarketManager().onTransaction(finalItemKey, true, finalAmount);

            double newBalance = plugin.getEconomyManager().getBalance(onlinePlayer, finalCurrency);
            String formatted = plugin.getEconomyManager().format(finalCost, finalCurrency);
            String balFormatted = plugin.getEconomyManager().format(newBalance, finalCurrency);

            onlinePlayer.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                    "<green><bold>✔</bold> Web purchase: <white>" + finalAmount + "x "
                            + finalMaterial.name().replace("_", " ") + "</white> for <gold>" + formatted
                            + "</gold></green>"));

            future.complete("{\"success\":true,\"spent\":" + jsonStr(formatted)
                    + ",\"newBalance\":" + newBalance
                    + ",\"newBalanceFormatted\":" + jsonStr(balFormatted) + "}");
        });

        try {
            String result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            int code = result.contains("\"error\"") ? 400 : 200;
            sendJson(exchange, code, result);
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"error\":\"Transaction timed out\"}");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private String buildItemsJson(List<MarketEntry> items, int page, int totalPages, int totalItems) {
        StringBuilder json = new StringBuilder();
        json.append("{\"page\":").append(page);
        json.append(",\"totalPages\":").append(totalPages);
        json.append(",\"totalItems\":").append(totalItems);
        json.append(",\"items\":[");

        for (int i = 0; i < items.size(); i++) {
            MarketEntry entry = items.get(i);
            if (i > 0)
                json.append(",");

            String key = (entry.material == Material.SPAWNER && entry.customName != null)
                    ? entry.customName
                    : entry.material.name();
            String displayName = entry.customName != null ? entry.customName
                    : entry.material.name().replace("_", " ");
            double buyPrice = (entry.material == Material.SPAWNER && entry.customName != null)
                    ? plugin.getMarketManager().getBuyPrice(entry.customName)
                    : plugin.getMarketManager().getBuyPrice(entry.material);
            String currency = (entry.material == Material.SPAWNER && entry.customName != null)
                    ? plugin.getMarketManager().getCurrency(entry.customName)
                    : plugin.getMarketManager().getCurrency(entry.material);

            json.append("{\"key\":").append(jsonStr(key));
            json.append(",\"material\":").append(jsonStr(entry.material.name().toLowerCase()));
            json.append(",\"name\":").append(jsonStr(displayName));
            json.append(",\"price\":").append(buyPrice);
            json.append(",\"priceFormatted\":").append(jsonStr(plugin.getEconomyManager().format(buyPrice, currency)));
            json.append(",\"currency\":").append(jsonStr(currency));
            json.append("}");
        }
        json.append("]}");
        return json.toString();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty())
            return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private int parseIntParam(Map<String, String> params, String key, int defaultVal) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultVal)));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonStr(String s) {
        if (s == null)
            return "null";
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}

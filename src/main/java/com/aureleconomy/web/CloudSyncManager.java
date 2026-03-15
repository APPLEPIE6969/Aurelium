package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Manages all communication between the MC plugin and the central
 * Render web server. Uses java.net.http.HttpClient (JDK 21, no deps).
 *
 * Lifecycle:
 * 1. register() — called once on startup
 * 2. startSync() — begins periodic data push + purchase polling
 * 3. stop() — cancels timers on shutdown
 */
public class CloudSyncManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final AurelEconomy plugin;
    private final HttpClient http;
    private final String baseUrl;
    private final String serverId;
    private final String apiKey;
    private final int syncInterval;

    private BukkitTask syncTask;
    private BukkitTask purchaseTask;
    private BukkitTask priceHistoryTask;
    private boolean registered = false;

    public CloudSyncManager(AurelEconomy plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60)) // 60s for Render cold start
                .build();

        this.baseUrl = plugin.getConfig().getString("web.cloud.url", "https://aurelium-web.onrender.com");
        this.syncInterval = plugin.getConfig().getInt("web.cloud.sync-interval", 30);

        // Auto-generate server-id and api-key if missing
        String id = plugin.getConfig().getString("web.cloud.server-id", "");
        String key = plugin.getConfig().getString("web.cloud.api-key", "");

        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8);
            plugin.getConfig().set("web.cloud.server-id", id);
            plugin.saveConfig();
        }
        if (key.isEmpty()) {
            key = UUID.randomUUID().toString().replace("-", "");
            plugin.getConfig().set("web.cloud.api-key", key);
            plugin.saveConfig();
        }

        this.serverId = id;
        this.apiKey = key;
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    /** Register with the Render server and start sync loop. */
    public void start() {
        // Register asynchronously with retries (Render free tier can take 30-60s to
        // wake)
        CompletableFuture.runAsync(() -> {
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    plugin.getComponentLogger().info("Cloud dashboard: registering (attempt " + attempt + "/5)...");
                    register();
                    registered = true;
                    plugin.getComponentLogger().info("Cloud dashboard registered — server ID: " + serverId);
                    // Do an initial sync immediately
                    try {
                        syncMarketData();
                    } catch (Exception ignored) {
                    }
                    return;
                } catch (Exception e) {
                    plugin.getComponentLogger().warn("Registration attempt " + attempt + " failed: " + e.getMessage());
                    if (attempt < 5) {
                        try {
                            Thread.sleep(15_000);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }
            plugin.getComponentLogger().error("Failed to register with cloud dashboard after 5 attempts at " + baseUrl);
        });

        // Sync market data periodically (async) — also retries registration if needed
        long syncTicks = syncInterval * 20L;
        syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!registered) {
                // Try to register on each sync tick if not yet registered
                try {
                    register();
                    registered = true;
                    plugin.getComponentLogger().info("Cloud dashboard registered (late) — server ID: " + serverId);
                } catch (Exception ignored) {
                    return;
                }
            }
            try {
                syncMarketData();
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Cloud sync failed: " + e.getMessage());
            }
        }, syncTicks, syncTicks);

        // Poll for pending purchases every 2 seconds (on main thread for safety)
        purchaseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!registered)
                return;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchPendingPurchases();
                } catch (Exception e) {
                    return Collections.<Map<String, Object>>emptyList();
                }
            }).thenAccept(pending -> {
                // Execute purchases on main thread
                Bukkit.getScheduler().runTask(plugin, () -> executePurchases(pending));
            });
        }, 40L, 40L); // 2 seconds

        // Record price snapshots every 10 minutes for stock charts
        priceHistoryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!registered)
                return;
            try {
                recordPriceSnapshot();
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Price history snapshot failed: " + e.getMessage());
            }
        }, 200L, 12000L); // Start after 10s, repeat every 10 min
    }

    public void stop() {
        if (syncTask != null)
            syncTask.cancel();
        if (purchaseTask != null)
            purchaseTask.cancel();
        if (priceHistoryTask != null)
            priceHistoryTask.cancel();
    }

    public boolean isRegistered() {
        return registered;
    }

    public String getServerId() {
        return serverId;
    }

    /** Build the dashboard URL for a player session. Posts session data async. */
    public String createSessionUrl(Player player) {
        String token = UUID.randomUUID().toString().replace("-", "");

        // Post session to Render asynchronously — frontend will retry until ready
        CompletableFuture.runAsync(() -> {
            try {
                String defaultCurrency = plugin.getEconomyManager().getDefaultCurrency();
                double balance = plugin.getEconomyManager().getBalance(player, defaultCurrency);

                StringBuilder balancesJson = new StringBuilder("{");
                balancesJson.append("\"").append(escJson(defaultCurrency)).append("\":").append(balance);

                if (plugin.getConfig().isConfigurationSection("economy.currencies")) {
                    for (String cur : plugin.getConfig()
                            .getConfigurationSection("economy.currencies").getKeys(false)) {
                        if (!cur.equals(defaultCurrency)) {
                            double bal = plugin.getEconomyManager().getBalance(player, cur);
                            balancesJson.append(",\"").append(escJson(cur)).append("\":").append(bal);
                        }
                    }
                }
                balancesJson.append("}");

                String json = "{\"token\":\"" + token + "\""
                        + ",\"playerUuid\":\"" + player.getUniqueId() + "\""
                        + ",\"playerName\":\"" + escJson(player.getName()) + "\""
                        + ",\"balances\":" + balancesJson
                        + ",\"defaultCurrency\":\"" + escJson(defaultCurrency) + "\""
                        + ",\"serverId\":\"" + escJson(serverId) + "\"}";

                postJson("/api/session", json);
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Failed to create cloud session: " + e.getMessage());
            }
        });

        return baseUrl + "/shop/" + serverId + "?token=" + token;
    }

    // ── Registration ─────────────────────────────────────────────────

    private void register() throws Exception {
        String serverName = plugin.getServer().getName();

        String json = "{\"serverId\":\"" + escJson(serverId) + "\""
                + ",\"apiKey\":\"" + escJson(apiKey) + "\""
                + ",\"serverName\":\"" + escJson(serverName) + "\"}";

        postJson("/api/register", json);
    }

    // ── Data Sync ────────────────────────────────────────────────────

    private void syncMarketData() throws Exception {
        StringBuilder json = new StringBuilder();
        json.append("{\"serverId\":\"").append(escJson(serverId)).append("\"");

        // Categories
        json.append(",\"categories\":[");
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            Category cat = cats[i];
            if (i > 0)
                json.append(",");
            json.append("{\"id\":\"").append(escJson(cat.name())).append("\"");
            json.append(",\"name\":\"").append(escJson(cat.name)).append("\"");
            json.append(",\"icon\":\"").append(escJson(cat.icon.name().toLowerCase())).append("\"");
            json.append(",\"itemCount\":").append(MarketItems.getItems(cat).size());
            json.append("}");
        }
        json.append("]");

        // Items grouped by category
        json.append(",\"items\":{");
        for (int c = 0; c < cats.length; c++) {
            Category cat = cats[c];
            if (c > 0)
                json.append(",");
            json.append("\"").append(escJson(cat.name())).append("\":[");

            List<MarketEntry> entries = MarketItems.getItems(cat).stream()
                    .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                    .toList();

            for (int i = 0; i < entries.size(); i++) {
                MarketEntry entry = entries.get(i);
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

                json.append("{\"key\":\"").append(escJson(key)).append("\"");
                json.append(",\"material\":\"").append(escJson(entry.material.name().toLowerCase())).append("\"");
                json.append(",\"name\":\"").append(escJson(displayName)).append("\"");
                json.append(",\"price\":").append(buyPrice);
                json.append(",\"priceFormatted\":\"")
                        .append(escJson(plugin.getEconomyManager().format(buyPrice, currency))).append("\"");
                json.append(",\"currency\":\"").append(escJson(currency)).append("\"");
                json.append(",\"currencySymbol\":\"")
                        .append(escJson(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
                json.append("}");
            }
            json.append("]");
        }
        json.append("}");

        // ── Auctions ────────────────────────────────────────────────
        json.append(",\"auctions\":[");
        List<com.aureleconomy.auction.AuctionItem> auctions = plugin.getAuctionManager().getActiveAuctions();
        for (int i = 0; i < auctions.size(); i++) {
            com.aureleconomy.auction.AuctionItem ai = auctions.get(i);
            if (i > 0)
                json.append(",");
            String sellerName = resolvePlayerName(ai.getSeller());
            String itemName = ai.getItem().getType().name().replace("_", " ");
            // Use custom display name if present
            if (ai.getItem().hasItemMeta() && ai.getItem().getItemMeta().hasDisplayName()) {
                itemName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(ai.getItem().getItemMeta().displayName());
            }
            json.append("{\"id\":").append(ai.getId());
            json.append(",\"seller\":\"").append(escJson(sellerName)).append("\"");
            json.append(",\"sellerUuid\":\"").append(ai.getSeller().toString()).append("\"");
            json.append(",\"itemName\":\"").append(escJson(itemName)).append("\"");
            json.append(",\"material\":\"").append(escJson(ai.getItem().getType().name().toLowerCase())).append("\"");
            json.append(",\"amount\":").append(ai.getItem().getAmount());
            json.append(",\"price\":").append(ai.getPrice());
            json.append(",\"currency\":\"").append(escJson(ai.getCurrency())).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(escJson(plugin.getEconomyManager().getCurrencySymbol(ai.getCurrency()))).append("\"");
            json.append(",\"isBin\":").append(ai.isBin());
            json.append(",\"expiration\":").append(ai.getExpiration());
            json.append(",\"startTime\":").append(ai.getStartTime());
            String bidderName = ai.getHighestBidder() != null ? resolvePlayerName(ai.getHighestBidder()) : "";
            json.append(",\"highestBidder\":\"").append(escJson(bidderName)).append("\"");
            json.append("}");
        }
        json.append("]");

        // ── Buy Orders ──────────────────────────────────────────────
        json.append(",\"orders\":[");
        var orders = plugin.getOrderManager().getActiveOrders();
        int oi = 0;
        for (var order : orders) {
            if (oi++ > 0)
                json.append(",");
            String buyerName = resolvePlayerName(order.getBuyerUuid());
            json.append("{\"id\":").append(order.getId());
            json.append(",\"buyer\":\"").append(escJson(buyerName)).append("\"");
            json.append(",\"buyerUuid\":\"").append(order.getBuyerUuid().toString()).append("\"");
            json.append(",\"material\":\"").append(escJson(order.getMaterial().name().toLowerCase())).append("\"");
            json.append(",\"itemName\":\"").append(escJson(order.getMaterial().name().replace("_", " "))).append("\"");
            json.append(",\"amountRequested\":").append(order.getAmountRequested());
            json.append(",\"amountFilled\":").append(order.getAmountFilled());
            json.append(",\"pricePerPiece\":").append(order.getPricePerPiece());
            json.append(",\"currency\":\"").append(escJson(order.getCurrency())).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(escJson(plugin.getEconomyManager().getCurrencySymbol(order.getCurrency()))).append("\"");
            json.append(",\"status\":\"").append(escJson(order.getStatus())).append("\"");
            json.append("}");
        }
        json.append("]");

        // ── Stocks / Price Tracker ──────────────────────────────────
        json.append(",\"stocks\":[");
        List<MarketEntry> allStocks = new ArrayList<>(plugin.getMarketManager().getEntryCache().values());
        for (int i = 0; i < allStocks.size(); i++) {
            MarketEntry entry = allStocks.get(i);

            // Skip blacklisted items
            if (plugin.getMarketManager().isBlacklisted(entry.material)) {
                continue;
            }

            if (json.charAt(json.length() - 1) != '[') {
                json.append(",");
            }

            String priceKey = (entry.customName != null) ? entry.customName : entry.material.name();
            String displayName = entry.customName != null ? entry.customName
                    : entry.material.name().replace("_", " ");

            double buyPrice = plugin.getMarketManager().getBuyPrice(priceKey);
            double sellPrice = plugin.getMarketManager().getSellPrice(priceKey);
            double basePrice = entry.price;

            // Non-market items: use last sold price
            if (basePrice == 1.0 && buyPrice <= 1.0) {
                Double lastSold = plugin.getOrderManager().getLastSoldPrice(priceKey);
                if (lastSold != null) {
                    buyPrice = lastSold;
                    sellPrice = lastSold;
                } else if (buyPrice == 1.0) {
                    // It's unvalued
                    buyPrice = 0;
                    sellPrice = 0;
                }
            }

            double change = 0;
            if (basePrice > 0 && buyPrice > 0 && basePrice != 1.0) {
                change = ((buyPrice - basePrice) / basePrice) * 100;
            } else if (basePrice == 1.0 && buyPrice > 0) {
                // For non-market items, we don't really have a "change" from base,
                // but we could mark it as 0 to avoid showing weird jumps.
                change = 0;
            }

            String currency = (entry.material == Material.SPAWNER && entry.customName != null)
                    ? plugin.getMarketManager().getCurrency(entry.customName)
                    : plugin.getMarketManager().getCurrency(entry.material);

            json.append("{\"key\":\"").append(escJson(priceKey)).append("\"");
            json.append(",\"material\":\"").append(escJson(entry.material.name().toLowerCase())).append("\"");
            json.append(",\"name\":\"").append(escJson(displayName)).append("\"");
            json.append(",\"buyPrice\":").append(buyPrice);
            json.append(",\"sellPrice\":").append(sellPrice);
            json.append(",\"change\":").append(change);
            json.append(",\"currency\":\"").append(escJson(currency)).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(escJson(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
            json.append("}");
        }
        json.append("]");

        // ── Price History (for charts) ──────────────────────────────
        json.append(",\"priceHistory\":");
        json.append(loadPriceHistoryJson());

        json.append("}");

        postJson("/api/sync", json.toString());
    }

    /** Resolve a UUID to a player name (online check + Bukkit cache). */
    private String resolvePlayerName(java.util.UUID uuid) {
        org.bukkit.entity.Player online = Bukkit.getPlayer(uuid);
        if (online != null)
            return online.getName();
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }

    // ── Price History ────────────────────────────────────────────────

    /** Record a snapshot of all item prices into the database. */
    private void recordPriceSnapshot() {
        long now = System.currentTimeMillis();
        List<MarketEntry> allItems = MarketItems.getItems(Category.ALL_ITEMS).stream()
                .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                .toList();

        try (var conn = plugin.getDatabaseManager().getConnection();
                var ps = conn.prepareStatement(
                        "INSERT INTO price_history (item_key, buy_price, sell_price, timestamp) VALUES (?, ?, ?, ?)")) {
            for (MarketEntry entry : allItems) {
                String priceKey = (entry.customName != null) ? entry.customName : entry.material.name();
                double buyPrice = plugin.getMarketManager().getBuyPrice(priceKey);
                double sellPrice = plugin.getMarketManager().getSellPrice(priceKey);

                // Skip non-market items with default prices
                if (entry.price == 1.0 && buyPrice == 1.0)
                    continue;

                ps.setString(1, priceKey);
                ps.setDouble(2, buyPrice);
                ps.setDouble(3, sellPrice);
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Price history record failed: " + e.getMessage());
        }

        // Clean old data (keep 7 days)
        try (var conn = plugin.getDatabaseManager().getConnection();
                var ps = conn.prepareStatement("DELETE FROM price_history WHERE timestamp < ?")) {
            ps.setLong(1, now - 7L * 24 * 60 * 60 * 1000);
            ps.executeUpdate();
        } catch (Exception ignored) {
        }
    }

    /**
     * Load price history from DB as JSON object: { "DIAMOND": [{t:123,b:50,s:40},
     * ...], ... }
     */
    private String loadPriceHistoryJson() {
        StringBuilder sb = new StringBuilder("{");
        // Query last 7 days, grouped by item
        long cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
        try (var conn = plugin.getDatabaseManager().getConnection();
                var ps = conn.prepareStatement(
                        "SELECT item_key, buy_price, sell_price, timestamp FROM price_history " +
                                "WHERE timestamp > ? ORDER BY timestamp ASC")) {
            ps.setLong(1, cutoff);
            var rs = ps.executeQuery();

            Map<String, List<String>> grouped = new LinkedHashMap<>();
            while (rs.next()) {
                String key = rs.getString("item_key");
                double bp = rs.getDouble("buy_price");
                double sp = rs.getDouble("sell_price");
                long ts = rs.getLong("timestamp");
                grouped.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new StringBuilder().append("{\"t\":").append(ts)
                                .append(",\"b\":").append(bp)
                                .append(",\"s\":").append(sp)
                                .append("}").toString());
            }

            int idx = 0;
            for (var e : grouped.entrySet()) {
                if (idx++ > 0)
                    sb.append(",");
                sb.append("\"").append(escJson(e.getKey())).append("\":[");
                sb.append(String.join(",", e.getValue()));
                sb.append("]");
            }
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Price history load failed: " + e.getMessage());
        }
        sb.append("}");
        return sb.toString();
    }

    // ── Purchase Polling ─────────────────────────────────────────────

    private List<Map<String, Object>> fetchPendingPurchases() throws Exception {
        // The sync endpoint returns pending purchases in its response
        // But we can also use a dedicated call — for now, sync response is enough
        // This manual poll is a fallback
        String url = baseUrl + "/api/sync?serverId=" + serverId;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"serverId\":\"" + escJson(serverId) + "\"}"))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            return Collections.emptyList();

        // Parse pendingPurchases from response (simple JSON parsing)
        return parsePendingPurchases(resp.body());
    }

    private void executePurchases(List<Map<String, Object>> pending) {
        for (Map<String, Object> purchase : pending) {
            String purchaseId = (String) purchase.get("id");
            String playerUuid = (String) purchase.get("playerUuid");
            String type = (String) purchase.getOrDefault("type", "buy");

            Player player = Bukkit.getPlayer(UUID.fromString(playerUuid));
            if (player == null || !player.isOnline()) {
                confirmPurchase(purchaseId, false, 0, "Player offline");
                continue;
            }

            if ("bid".equals(type)) {
                executeAuctionWebBid(player, purchase, purchaseId);
            } else if ("fill_order".equals(type)) {
                executeOrderWebFill(player, purchase, purchaseId);
            } else {
                executeMarketWebBuy(player, purchase, purchaseId);
            }
        }
    }

    private void executeAuctionWebBid(Player player, Map<String, Object> purchase, String purchaseId) {
        int auctionId = 0;
        if (purchase.get("auctionId") instanceof Number) {
            auctionId = ((Number) purchase.get("auctionId")).intValue();
        } else if (purchase.get("auctionId") instanceof String) {
            try {
                auctionId = Integer.parseInt((String) purchase.get("auctionId"));
            } catch (Exception ignored) {
            }
        }

        double amount = 0;
        if (purchase.get("amount") instanceof Number) {
            amount = ((Number) purchase.get("amount")).doubleValue();
        } else if (purchase.get("amount") instanceof String) {
            try {
                amount = Double.parseDouble((String) purchase.get("amount"));
            } catch (Exception ignored) {
            }
        }

        com.aureleconomy.auction.AuctionItem auction = plugin.getAuctionManager().getAuctionById(auctionId);
        if (auction == null || auction.isEnded()) {
            confirmPurchase(purchaseId, false, 0, "Auction ended or invalid");
            return;
        }

        if (auction.getSeller().equals(player.getUniqueId())) {
            confirmPurchase(purchaseId, false, 0, "You cannot buy your own auction");
            return;
        }

        if (!plugin.getEconomyManager().has(player, amount, auction.getCurrency())) {
            confirmPurchase(purchaseId, false, 0, "Not enough funds");
            return;
        }

        if (auction.isBin()) {
            if (amount < auction.getPrice()) {
                confirmPurchase(purchaseId, false, 0, "Amount below BIN price");
                return;
            }
            if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), auction.getItem(),
                    auction.getItem().getAmount())) {
                confirmPurchase(purchaseId, false, 0, "Inventory full");
                return;
            }
            auction.setEnded(true);
            plugin.getEconomyManager().withdraw(player, amount, auction.getCurrency());
            plugin.getAuctionManager().bid(auction, player.getUniqueId(), amount);
            plugin.getAuctionManager().endAuction(auction);
            player.getInventory().addItem(auction.getItem().clone());
            plugin.getAuctionManager().markCollected(auction.getId());

            player.sendMessage(MM.deserialize("<green><bold>✔</bold> Web purchase: <white>"
                    + auction.getItem().getAmount() + "x " + auction.getItem().getType().name().replace("_", " ")
                    + "</white> from Auction House</green>"));
        } else {
            if (amount <= auction.getPrice()) {
                confirmPurchase(purchaseId, false, 0, "Bid must be higher than current price");
                return;
            }
            plugin.getAuctionManager().bid(auction, player.getUniqueId(), amount);
            player.sendMessage(MM.deserialize("<green><bold>✔</bold> Web bid placed: <gold>"
                    + plugin.getEconomyManager().format(amount, auction.getCurrency()) + "</gold> on <white>"
                    + auction.getItem().getType().name().replace("_", " ") + "</white></green>"));
        }

        double newBalance = plugin.getEconomyManager().getBalance(player, auction.getCurrency());
        String formatted = plugin.getEconomyManager().format(amount, auction.getCurrency());
        confirmPurchase(purchaseId, true, newBalance, formatted);
    }

    private void executeMarketWebBuy(Player player, Map<String, Object> purchase, String purchaseId) {
        String itemKey = (String) purchase.get("item");
        int amount = ((Number) purchase.get("amount")).intValue();

        // Resolve item
        double buyPrice;
        String currency;
        Material material;

        try {
            material = Material.valueOf(itemKey.toUpperCase());
            buyPrice = plugin.getMarketManager().getBuyPrice(material);
            currency = plugin.getMarketManager().getCurrency(material);
        } catch (IllegalArgumentException e) {
            buyPrice = plugin.getMarketManager().getBuyPrice(itemKey);
            currency = plugin.getMarketManager().getCurrency(itemKey);
            material = Material.SPAWNER;
        }

        if (buyPrice <= 0) {
            confirmPurchase(purchaseId, false, 0, "Item not for sale");
            return;
        }

        double totalCost = buyPrice * amount;

        if (!plugin.getEconomyManager().has(player, totalCost, currency)) {
            confirmPurchase(purchaseId, false, 0, "Not enough funds");
            return;
        }

        ItemStack toGive = new ItemStack(material, amount);
        if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), toGive, amount)) {
            confirmPurchase(purchaseId, false, 0, "Inventory full");
            return;
        }

        // Execute purchase
        plugin.getEconomyManager().withdraw(player, totalCost, currency);
        player.getInventory().addItem(toGive);
        plugin.getMarketManager().onTransaction(itemKey, true, amount);

        double newBalance = plugin.getEconomyManager().getBalance(player, currency);
        String formatted = plugin.getEconomyManager().format(totalCost, currency);

        player.sendMessage(MM.deserialize(
                "<green><bold>✔</bold> Web purchase: <white>" + amount + "x "
                        + material.name().replace("_", " ") + "</white> for <gold>"
                        + formatted + "</gold></green>"));

        confirmPurchase(purchaseId, true, newBalance, formatted);
    }

    private void executeOrderWebFill(Player seller, Map<String, Object> purchase, String purchaseId) {
        int orderId = 0;
        if (purchase.get("orderId") instanceof Number) {
            orderId = ((Number) purchase.get("orderId")).intValue();
        } else if (purchase.get("orderId") instanceof String) {
            try {
                orderId = Integer.parseInt((String) purchase.get("orderId"));
            } catch (Exception ignored) {
            }
        }

        int amount = 0;
        if (purchase.get("amount") instanceof Number) {
            amount = ((Number) purchase.get("amount")).intValue();
        } else if (purchase.get("amount") instanceof String) {
            try {
                amount = Integer.parseInt((String) purchase.get("amount"));
            } catch (Exception ignored) {
            }
        }

        if (amount <= 0) {
            confirmPurchase(purchaseId, false, 0, "Amount must be positive");
            return;
        }

        // Find the active order
        com.aureleconomy.orders.BuyOrder order = null;
        for (com.aureleconomy.orders.BuyOrder o : plugin.getOrderManager().getActiveOrders()) {
            if (o.getId() == orderId) {
                order = o;
                break;
            }
        }

        if (order == null || order.getAmountRemaining() <= 0) {
            confirmPurchase(purchaseId, false, 0, "Order is fully filled or invalid");
            return;
        }

        if (order.getBuyerUuid().equals(seller.getUniqueId())) {
            confirmPurchase(purchaseId, false, 0, "You cannot fill your own order");
            return;
        }

        // Verify seller has the items using OrderManager's countItems logic
        // Because countItems is private, we'll replicate the simple inventory loop
        int playerHas = 0;
        for (ItemStack item : seller.getInventory().getContents()) {
            if (item != null && item.getType() == order.getMaterial()) {
                playerHas += item.getAmount();
            }
        }

        if (playerHas < amount) {
            confirmPurchase(purchaseId, false, 0, "Not enough items in inventory");
            return;
        }

        // Defer to the OrderManager to handle the complex fulfillment tasks
        // (tax calculation, database updates, collection bin delivery, buyer
        // notification)
        plugin.getOrderManager().fillOrder(seller, orderId, amount);

        double newBalance = plugin.getEconomyManager().getBalance(seller, order.getCurrency());
        String formatted = plugin.getEconomyManager().format(amount * order.getPricePerPiece(), order.getCurrency());
        confirmPurchase(purchaseId, true, newBalance, formatted);
    }

    private void confirmPurchase(String purchaseId, boolean success, double newBalance, String spent) {
        CompletableFuture.runAsync(() -> {
            try {
                String json = "{\"purchaseId\":\"" + escJson(purchaseId) + "\""
                        + ",\"serverId\":\"" + escJson(serverId) + "\""
                        + ",\"success\":" + success
                        + ",\"newBalance\":" + newBalance
                        + ",\"spent\":\"" + escJson(spent) + "\"}";
                postJson("/api/confirm-purchase", json);
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Failed to confirm purchase: " + e.getMessage());
            }
        });
    }

    // ── HTTP Helpers ─────────────────────────────────────────────────

    private String postJson(String endpoint, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            if (resp.statusCode() == 503 && resp.body().contains("\"queued\":true")) {
                String body = resp.body();
                int posIndex = body.indexOf("\"position\":");
                String pos = "?";
                if (posIndex != -1) {
                    int start = posIndex + 11;
                    int end = body.indexOf("}", start);
                    if (end != -1)
                        pos = body.substring(start, end).trim();
                }
                throw new RuntimeException("Dashboard Waitlist active. Waiting in queue (Position: " + pos + ").");
            }
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static String escJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Very simple JSON parser for the pendingPurchases array.
     * Avoids external dependencies (Gson, Jackson).
     */

    private List<Map<String, Object>> parsePendingPurchases(String jsonBody) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Find "pendingPurchases":[...]
        int idx = jsonBody.indexOf("\"pendingPurchases\"");
        if (idx < 0)
            return result;

        int arrStart = jsonBody.indexOf('[', idx);
        if (arrStart < 0)
            return result;

        int arrEnd = jsonBody.indexOf(']', arrStart);
        if (arrEnd < 0)
            return result;

        String arrStr = jsonBody.substring(arrStart + 1, arrEnd).trim();
        if (arrStr.isEmpty())
            return result;

        // Split objects by },{
        String[] objects = arrStr.split("\\},\\s*\\{");
        for (String obj : objects) {
            obj = obj.replace("{", "").replace("}", "").trim();
            Map<String, Object> map = new HashMap<>();

            String[] pairs = obj.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");

                    // Try to parse as number
                    try {
                        if (value.contains(".")) {
                            map.put(key, Double.parseDouble(value));
                        } else {
                            map.put(key, Integer.parseInt(value));
                        }
                    } catch (NumberFormatException e) {
                        map.put(key, value);
                    }
                }
            }

            if (map.containsKey("id") && map.containsKey("playerUuid")) {
                result.add(map);
            }
        }

        return result;
    }
}

package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.security.SecureRandom;

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
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final AurelEconomy plugin;
    private final HttpClient http;
    private final String baseUrl;
    private final String serverId;
    private final String apiKey;
    private String prevApiKey;
    private final String registrationSecret;
    private final int syncInterval;

    private BukkitTask syncTask;
    private BukkitTask purchaseTask;
    private BukkitTask priceHistoryTask;
    private boolean registered = false;
    private boolean registrationFailed = false;

    public CloudSyncManager(AurelEconomy plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

        this.baseUrl = plugin.getConfig().getString("web.cloud.url", "https://webaureliummc.onrender.com");
        this.syncInterval = plugin.getConfig().getInt("web.cloud.sync-interval", 30);

        String id = plugin.getConfig().getString("web.cloud.server-id", "");
        String key = plugin.getConfig().getString("web.cloud.api-key", "");
        String prevKey = plugin.getConfig().getString("web.cloud.prev-api-key", "");
        String regSecret = plugin.getConfig().getString("web.cloud.registration-secret", "");

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
        this.prevApiKey = prevKey;
        this.registrationSecret = regSecret;
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    public void start() {
        attemptRegistration(1);

        long syncTicks = syncInterval * 20L;
        syncTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!registered && !registrationFailed) {
                try {
                    register();
                    registered = true;
                    plugin.getComponentLogger().info("Cloud dashboard registered (late) — server ID: " + serverId);
                } catch (Exception e) {
                    String msg = e.getMessage();
                    if (msg != null && (msg.contains("HTTP 403") || msg.contains("HTTP 401"))) {
                        registrationFailed = true;
                        plugin.getComponentLogger().error("Cloud dashboard registration permanently failed (auth error): " + msg);
                        plugin.getComponentLogger().error("If your server-id changed, delete the old entry from the dashboard or set web.cloud.registration-secret in config.");
                    }
                    return;
                }
            }
            try {
                syncMarketData();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("received no bytes") || msg.contains("Connection reset") || msg.contains("timed out"))) {
                    plugin.getComponentLogger().warn("Cloud sync: Server is likely waking up or connection reset. Retrying in " + syncInterval + "s...");
                } else {
                    plugin.getComponentLogger().warn("Cloud sync failed: " + msg);
                }
            }
        }, syncTicks, syncTicks);

        purchaseTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!registered)
                return;
            CompletableFuture.supplyAsync(() -> {
                try {
                    return fetchPendingPurchases();
                } catch (Exception e) {
                    if (e.getMessage() != null && !e.getMessage().contains("Dash")) {
                        plugin.getComponentLogger().warn("Purchase fetch failed: " + e.getMessage());
                    }
                    return Collections.<Map<String, Object>>emptyList();
                }
            }).thenAccept(pending -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    executePurchases(pending);
                    if (!pending.isEmpty()) {
                        CompletableFuture.runAsync(() -> {
                            try {
                                syncMarketData();
                            } catch (Exception ignored) {
                            }
                        });
                    }
                });
            });
        }, 40L, 40L);

        priceHistoryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!registered)
                return;
            try {
                recordPriceSnapshot();
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Price history snapshot failed: " + e.getMessage());
            }
        }, 200L, 12000L);
    }

    private void attemptRegistration(int attempt) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                plugin.getComponentLogger().info("Cloud dashboard: registering (attempt " + attempt + "/3)...");
                register();
                registered = true;
                plugin.getComponentLogger().info("Cloud dashboard registered — server ID: " + serverId);
                try {
                    syncMarketData();
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg != null && (msg.contains("HTTP 403") || msg.contains("HTTP 401"))) {
                    registrationFailed = true;
                    plugin.getComponentLogger().error("Cloud dashboard registration failed (auth error): " + msg);
                    plugin.getComponentLogger().error("Cloud dashboard disabled. Your server-id may have a stale entry with a different API key.");
                    plugin.getComponentLogger().error("Fix: delete the old server entry from the dashboard, or set web.cloud.registration-secret in config.yml to match the dashboard REGISTRATION_SECRET env var.");
                } else if (msg != null && (msg.contains("HTTP 4") || msg.contains("HTTP 5") || msg.contains("http 4") || msg.contains("http 5"))) {
                    registrationFailed = true;
                    plugin.getComponentLogger().error("Cloud dashboard registration failed (dashboard returned error): " + msg);
                    plugin.getComponentLogger().error("Cloud dashboard disabled. Set web.cloud.url in config if you have a dashboard.");
                } else {
                    plugin.getComponentLogger().error("Registration attempt " + attempt + " failed (transient): " + msg);
                    if (attempt < 3) {
                        long delay = 300L * attempt;
                        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> attemptRegistration(attempt + 1), delay);
                    } else {
                        plugin.getComponentLogger().error("Cloud dashboard registration gave up after " + attempt + " attempts");
                    }
                }
            }
        });
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

    public String createSessionUrl(Player player) {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        CompletableFuture.runAsync(() -> {
            try {
                String sessionJson = buildPlayerJson(player, token);
                postJson("/api/session", sessionJson);
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Failed to create cloud session: " + e.getMessage());
            }
        });

        return baseUrl + "/shop/" + serverId + "?token=" + token;
    }

    public void updatePlayerBalance(Player player) {
        if (!registered) return;

        CompletableFuture.runAsync(() -> {
            try {
                String json = buildPlayerJson(player, null);
                postJson("/api/session-update", json);
            } catch (Exception e) {
            }
        });
    }

    private String buildPlayerJson(Player player, String token) {
        String defaultCurrency = plugin.getEconomyManager().getDefaultCurrency();
        BigDecimal balance = plugin.getEconomyManager().getBalance(player, defaultCurrency);

        StringBuilder balancesJson = new StringBuilder("{");
        balancesJson.append("\"").append(escJson(defaultCurrency)).append("\":").append(balance);

        if (plugin.getConfig().isConfigurationSection("economy.currencies")) {
            for (String cur : plugin.getConfig()
                    .getConfigurationSection("economy.currencies").getKeys(false)) {
                if (!cur.equals(defaultCurrency)) {
                    BigDecimal bal = plugin.getEconomyManager().getBalance(player, cur);
                    balancesJson.append(",\"").append(escJson(cur)).append("\":").append(bal);
                }
            }
        }
        balancesJson.append("}");

        StringBuilder json = new StringBuilder();
        json.append("{\"playerUuid\":\"").append(player.getUniqueId()).append("\"")
                .append(",\"playerName\":\"").append(escJson(player.getName())).append("\"")
                .append(",\"balances\":").append(balancesJson)
                .append(",\"defaultCurrency\":\"").append(escJson(defaultCurrency)).append("\"")
                .append(",\"serverId\":\"").append(escJson(serverId)).append("\"");

        if (token != null) {
            json.append(",\"token\":\"").append(token).append("\"");
        }

        json.append("}");
        return json.toString();
    }

    // ── Registration ─────────────────────────────────────────────────

    private void register() throws Exception {
        String serverName = plugin.getServer().getName();

        StringBuilder json = new StringBuilder();
        json.append("{\"serverId\":\"").append(escJson(serverId)).append("\"")
                .append(",\"apiKey\":\"").append(escJson(apiKey)).append("\"")
                .append(",\"serverName\":\"").append(escJson(serverName)).append("\"}");

        String response = postJsonWithCurrentKey("/api/register", json.toString());

        // If re-registered (key rotation), update prev-api-key to current key for future rotations
        if (response.contains("\"reRegistered\":true")) {
            plugin.getConfig().set("web.cloud.prev-api-key", apiKey);
            plugin.saveConfig();
            this.prevApiKey = apiKey;
            plugin.getComponentLogger().info("Cloud dashboard: API key rotated, prev-api-key updated");
        }
    }

    private String postJsonWithCurrentKey(String endpoint, String json) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + endpoint))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(60));

        // Send previous API key as proof of ownership for key rotation
        if (prevApiKey != null && !prevApiKey.isEmpty()) {
            req.header("X-Current-Api-Key", prevApiKey);
        }

        // Send registration secret for authorized key rotation
        if (registrationSecret != null && !registrationSecret.isEmpty()) {
            req.header("X-Registration-Secret", registrationSecret);
        }

        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            String body = resp.body();
            if (resp.statusCode() == 503 && body.contains("\"queued\":true")) {
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

            if (resp.statusCode() == 403 && body.contains("Invalid server ID or API key")) {
                this.registered = false;
            }

            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new RuntimeException("HTTP " + resp.statusCode() + " (" + endpoint + "): " + snippet);
        }
        return resp.body();
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
                BigDecimal buyPrice = (entry.material == Material.SPAWNER && entry.customName != null)
                        ? plugin.getMarketManager().getBuyPrice(entry.customName)
                        : plugin.getMarketManager().getBuyPrice(entry.material);
                String currency = (entry.material == Material.SPAWNER && entry.customName != null)
                        ? plugin.getMarketManager().getCurrency(entry.customName)
                        : plugin.getMarketManager().getCurrency(entry.material);

                json.append("{\"key\":\"").append(escJson(key)).append("\"");
                json.append(",\"material\":\"").append(escJson(entry.material.name().toLowerCase())).append("\"");
                json.append(",\"name\":\"").append(escJson(displayName)).append("\"");
                json.append(",\"price\":").append(buyPrice.doubleValue());
                json.append(",\"priceFormatted\":\"")
                        .append(escJson(plugin.getEconomyManager().getFormattedWithSymbol(buyPrice, currency))).append("\"");
                json.append(",\"currency\":\"").append(escJson(currency)).append("\"");
                json.append(",\"currencySymbol\":\"")
                        .append(escJson(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
                json.append("}");
            }
            json.append("]");
        }
        json.append("}");

        // ── Auctions
        json.append(",\"auctions\":[");
        List<com.aureleconomy.auction.AuctionItem> auctions = plugin.getAuctionManager().getActiveAuctions();
        for (int i = 0; i < auctions.size(); i++) {
            com.aureleconomy.auction.AuctionItem ai = auctions.get(i);
            if (i > 0)
                json.append(",");
            String sellerName = resolvePlayerName(ai.getSeller());
            String itemName = ai.getItem().getType().name().replace("_", " ");
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
            json.append(",\"purchaseMode\":\"").append(escJson(ai.getPurchaseMode().name())).append("\"");
            json.append(",\"remaining\":").append(ai.getAvailableQuantity());
            json.append(",\"expiration\":").append(ai.getExpiration());
            json.append(",\"startTime\":").append(ai.getStartTime());
            String bidderName = ai.getHighestBidder() != null ? resolvePlayerName(ai.getHighestBidder()) : "";
            json.append(",\"highestBidder\":\"").append(escJson(bidderName)).append("\"");
            json.append("}");
        }
        json.append("]");

        // ── Buy Orders
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

        // ── Stocks / Price Tracker
        json.append(",\"stocks\":[");
        List<MarketEntry> allStocks = new ArrayList<>(plugin.getMarketManager().getEntryCache().values());
        for (int i = 0; i < allStocks.size(); i++) {
            MarketEntry entry = allStocks.get(i);

            if (plugin.getMarketManager().isBlacklisted(entry.material)) {
                continue;
            }

            if (json.charAt(json.length() - 1) != '[') {
                json.append(",");
            }

            String priceKey = (entry.customName != null) ? entry.customName : entry.material.name();
            String displayName = entry.customName != null ? entry.customName
                    : entry.material.name().replace("_", " ");

            BigDecimal buyPrice = plugin.getMarketManager().getBuyPrice(priceKey);
            BigDecimal sellPrice = plugin.getMarketManager().getSellPrice(priceKey);
            BigDecimal basePrice = entry.price;

            if (basePrice.compareTo(BigDecimal.ONE) == 0 && buyPrice.compareTo(BigDecimal.ONE) <= 0) {
                BigDecimal lastSold = plugin.getOrderManager().getLastSoldPrice(priceKey);
                if (lastSold != null) {
                    buyPrice = lastSold;
                    sellPrice = lastSold;
                } else if (buyPrice.compareTo(BigDecimal.ONE) == 0) {
                    buyPrice = BigDecimal.ZERO;
                    sellPrice = BigDecimal.ZERO;
                }
            }

            BigDecimal change = BigDecimal.ZERO;
            if (basePrice.compareTo(BigDecimal.ZERO) > 0 && buyPrice.compareTo(BigDecimal.ZERO) > 0 && basePrice.compareTo(BigDecimal.ONE) != 0) {
                change = buyPrice.subtract(basePrice)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(basePrice, 4, RoundingMode.HALF_UP);
            }

            String currency = (entry.material == Material.SPAWNER && entry.customName != null)
                    ? plugin.getMarketManager().getCurrency(entry.customName)
                    : plugin.getMarketManager().getCurrency(entry.material);

            json.append("{\"key\":\"").append(escJson(priceKey)).append("\"");
            json.append(",\"material\":\"").append(escJson(entry.material.name().toLowerCase())).append("\"");
            json.append(",\"name\":\"").append(escJson(displayName)).append("\"");
            json.append(",\"buyPrice\":").append(buyPrice.doubleValue());
            json.append(",\"sellPrice\":").append(sellPrice.doubleValue());
            json.append(",\"change\":").append(change.doubleValue());
            json.append(",\"currency\":\"").append(escJson(currency)).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(escJson(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
            json.append("}");
        }
        json.append("]");

        // ── Custom Items (scanner)
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        boolean hasCustomItems = registry != null && !registry.isEmpty();
        json.append(",\"hasCustomItems\":").append(hasCustomItems);
        if (hasCustomItems) {
            json.append(",\"customItems\":[");
            int ci = 0;
            for (CustomMarketItem cmi : registry.getAllItems()) {
                if (ci++ > 0) json.append(",");
                String cName = cmi.getDisplayName() != null ? cmi.getDisplayName() : cmi.getCanonicalId();
                String material = cmi.getItemStack().getType().name().toLowerCase();
                BigDecimal buy = cmi.getBuyPrice();
                BigDecimal sell = cmi.getSellPrice();
                String currency = plugin.getEconomyManager().getDefaultCurrency();
                String symbol = plugin.getEconomyManager().getCurrencySymbol(currency);
                json.append("{\"id\":\"").append(escJson(cmi.getCanonicalId())).append("\"");
                json.append(",\"name\":\"").append(escJson(cName)).append("\"");
                json.append(",\"material\":\"").append(escJson(material)).append("\"");
                json.append(",\"buyPrice\":").append(buy.doubleValue());
                json.append(",\"sellPrice\":").append(sell.doubleValue());
                json.append(",\"currency\":\"").append(escJson(currency)).append("\"");
                json.append(",\"currencySymbol\":\"").append(escJson(symbol)).append("\"");
                json.append("}");
            }
            json.append("]");
        }

        // ── Price History
        json.append(",\"priceHistory\":");
        json.append(loadPriceHistoryJson());

        json.append("}");

        postJson("/api/sync", json.toString());
    }

    private String resolvePlayerName(java.util.UUID uuid) {
        org.bukkit.entity.Player online = Bukkit.getPlayer(uuid);
        if (online != null)
            return online.getName();
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }

    // ── Price History ────────────────────────────────────────────────

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
                BigDecimal buyPrice = plugin.getMarketManager().getBuyPrice(priceKey);
                BigDecimal sellPrice = plugin.getMarketManager().getSellPrice(priceKey);

                if (entry.price.compareTo(BigDecimal.ONE) == 0 && buyPrice.compareTo(BigDecimal.ONE) == 0)
                    continue;

                ps.setString(1, priceKey);
                ps.setBigDecimal(2, buyPrice);
                ps.setBigDecimal(3, sellPrice);
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Price history record failed: " + e.getMessage());
        }

        try (var conn = plugin.getDatabaseManager().getConnection();
             var ps = conn.prepareStatement("DELETE FROM price_history WHERE timestamp < ?")) {
            ps.setLong(1, now - 7L * 24 * 60 * 60 * 1000);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Failed to prune old price history: " + e.getMessage());
        }
    }

    private String loadPriceHistoryJson() {
        StringBuilder sb = new StringBuilder("{");
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
                StringBuilder entry = new StringBuilder();
                entry.append("{\"t\":").append(ts)
                        .append(",\"b\":").append(bp)
                        .append(",\"s\":").append(sp)
                        .append("}");
                grouped.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(entry.toString());
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

        String body = resp.body();
        if (body.contains("\"pendingPurchases\":[")) {
            return parsePendingPurchases(body);
        }
        return Collections.emptyList();
    }

    /** Time-based expiry for processed purchase IDs. Key = purchaseId, Value = timestamp. */
    private final Map<String, Long> processedPurchases = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PURCHASE_EXPIRY_MS = 600_000L; // 10 minutes

    private void executePurchases(List<Map<String, Object>> pending) {
        long now = System.currentTimeMillis();

        // Purge expired entries
        var iter = processedPurchases.entrySet().iterator();
        while (iter.hasNext()) {
            if (now - iter.next().getValue() > PURCHASE_EXPIRY_MS) {
                iter.remove();
            }
        }

        for (Map<String, Object> purchase : pending) {
            String purchaseId = (String) purchase.get("id");
            if (purchaseId != null && processedPurchases.containsKey(purchaseId)) continue;

            String playerUuid = (String) purchase.get("playerUuid");
            String type = (String) purchase.getOrDefault("type", "buy");

            Player player = Bukkit.getPlayer(UUID.fromString(playerUuid));
            if (player == null || !player.isOnline()) {
                confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Player offline");
                continue;
            }

            processedPurchases.put(purchaseId, now);

            if ("bin".equals(type)) {
                executeAuctionWebBinPurchase(player, purchase, purchaseId);
            } else if ("bid".equals(type)) {
                executeAuctionWebBid(player, purchase, purchaseId);
            } else if ("fill_order".equals(type)) {
                executeOrderWebFill(player, purchase, purchaseId);
            } else {
                executeMarketWebBuy(player, purchase, purchaseId);
            }
        }
    }

    /**
     * Handle BIN auction purchases from the web dashboard.
     * Reads the "quantity" field for UNIT mode, or buys full stack for STACK mode.
     */
    private void executeAuctionWebBinPurchase(Player player, Map<String, Object> purchase, String purchaseId) {
        int auctionId = parseFieldInt(purchase, "auctionId", 0);

        com.aureleconomy.auction.AuctionItem auction = plugin.getAuctionManager().getAuctionById(auctionId);
        if (auction == null || auction.isEnded()) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Auction ended or invalid");
            return;
        }

        if (!auction.isBin()) {
            executeAuctionWebBid(player, purchase, purchaseId);
            return;
        }

        if (auction.getSeller().equals(player.getUniqueId())) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "You cannot buy your own auction");
            return;
        }

        if (auction.getPurchaseMode() == com.aureleconomy.auction.AuctionItem.PurchaseMode.UNIT) {
            // UNIT mode: buy specific quantity
            int quantity = parseFieldInt(purchase, "quantity", 1);
            quantity = Math.max(1, Math.min(quantity, auction.getAvailableQuantity()));

            BigDecimal unitPrice = auction.getPricePerUnit();
            BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
            String currency = auction.getCurrency();

            if (!plugin.getEconomyManager().has(player, totalCost, currency)) {
                confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Not enough funds");
                return;
            }

            int purchased = plugin.getAuctionManager().purchaseUnits(auction, player, quantity);
            if (purchased <= 0) {
                confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Purchase failed");
                return;
            }

            BigDecimal newBalance = plugin.getEconomyManager().getBalance(player, currency);
            String formatted = plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency);
            confirmPurchase(purchaseId, true, newBalance, formatted);
        } else {
            // STACK mode: buy entire stack
            BigDecimal totalCost = auction.getPrice();
            String currency = auction.getCurrency();

            if (!plugin.getEconomyManager().has(player, totalCost, currency)) {
                confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Not enough funds");
                return;
            }

            if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), auction.getItem(),
                    auction.getItem().getAmount())) {
                confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Inventory full");
                return;
            }

            if (!plugin.getAuctionManager().claimAuctionAtomic(auction.getId())) {
                confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Auction already sold");
                return;
            }

            plugin.getEconomyManager().withdraw(player, totalCost, currency);
            plugin.getAuctionManager().bid(auction, player.getUniqueId(), totalCost);
            plugin.getAuctionManager().endAuction(auction);
            player.getInventory().addItem(auction.getItem().clone());
            plugin.getAuctionManager().markCollectedAtomic(auction.getId());

            player.sendMessage(MM.deserialize("<green><bold>✔</bold> Web purchase: <white>"
                    + auction.getItem().getAmount() + "x " + auction.getItem().getType().name().replace("_", " ")
                    + "</white> from Auction House</green>"));

            BigDecimal newBalance = plugin.getEconomyManager().getBalance(player, currency);
            String formatted = plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency);
            confirmPurchase(purchaseId, true, newBalance, formatted);
        }
    }

    /**
     * Handle auction BIDS (non-BIN) from the web dashboard.
     */
    private void executeAuctionWebBid(Player player, Map<String, Object> purchase, String purchaseId) {
        int auctionId = parseFieldInt(purchase, "auctionId", 0);
        BigDecimal amount = parseFieldBigDecimal(purchase, "amount", BigDecimal.ZERO);

        com.aureleconomy.auction.AuctionItem auction = plugin.getAuctionManager().getAuctionById(auctionId);
        if (auction == null || auction.isEnded()) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Auction ended or invalid");
            return;
        }

        if (auction.getSeller().equals(player.getUniqueId())) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "You cannot bid on your own auction");
            return;
        }

        if (auction.isBin()) {
            executeAuctionWebBinPurchase(player, purchase, purchaseId);
            return;
        }

        if (!plugin.getEconomyManager().has(player, amount, auction.getCurrency())) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Not enough funds");
            return;
        }

        if (amount.compareTo(auction.getPrice()) <= 0) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Bid must be higher than current price");
            return;
        }
        plugin.getAuctionManager().bid(auction, player.getUniqueId(), amount);
        player.sendMessage(MM.deserialize("<green><bold>✔</bold> Web bid placed: <gold>"
                + plugin.getEconomyManager().getFormattedWithSymbol(amount, auction.getCurrency()) + "</gold> on <white>"
                + auction.getItem().getType().name().replace("_", " ") + "</white></green>"));

        BigDecimal newBalance = plugin.getEconomyManager().getBalance(player, auction.getCurrency());
        String formatted = plugin.getEconomyManager().getFormattedWithSymbol(amount, auction.getCurrency());
        confirmPurchase(purchaseId, true, newBalance, formatted);
    }

    /** Parse an int field from the purchase map, with fallback. */
    private int parseFieldInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    /** Parse a BigDecimal field from the purchase map, with fallback. */
    private BigDecimal parseFieldBigDecimal(Map<String, Object> map, String key, BigDecimal defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number) return BigDecimal.valueOf(((Number) val).doubleValue());
        if (val instanceof String) {
            try { return new BigDecimal((String) val); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    private void executeMarketWebBuy(Player player, Map<String, Object> purchase, String purchaseId) {
        String itemKey = (String) purchase.get("item");
        int amount = parseFieldInt(purchase, "amount", 1);

        BigDecimal buyPrice;
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

        if (buyPrice.compareTo(BigDecimal.ZERO) <= 0) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Item not for sale");
            return;
        }

        BigDecimal totalCost = buyPrice.multiply(BigDecimal.valueOf(amount));

        if (!plugin.getEconomyManager().has(player, totalCost, currency)) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Not enough funds");
            return;
        }

        ItemStack toGive = new ItemStack(material, amount);
        if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), toGive, amount)) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Inventory full");
            return;
        }

        plugin.getEconomyManager().withdraw(player, totalCost, currency);
        player.getInventory().addItem(toGive);
        plugin.getMarketManager().onTransaction(itemKey, true, amount);

        BigDecimal newBalance = plugin.getEconomyManager().getBalance(player, currency);
        String formatted = plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency);

        player.sendMessage(MM.deserialize(
                "<green><bold>✔</bold> Web purchase: <white>" + amount + "x "
                        + material.name().replace("_", " ") + "</white> for <gold>"
                        + formatted + "</gold></green>"));

        confirmPurchase(purchaseId, true, newBalance, formatted);
    }

    private void executeOrderWebFill(Player seller, Map<String, Object> purchase, String purchaseId) {
        int orderId = parseFieldInt(purchase, "orderId", 0);

        int amount = parseFieldInt(purchase, "amount", 0);

        if (amount <= 0) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Amount must be positive");
            return;
        }

        com.aureleconomy.orders.BuyOrder order = null;
        for (com.aureleconomy.orders.BuyOrder o : plugin.getOrderManager().getActiveOrders()) {
            if (o.getId() == orderId) {
                order = o;
                break;
            }
        }

        if (order == null || order.getAmountRemaining() <= 0) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Order is fully filled or invalid");
            return;
        }

        if (order.getBuyerUuid().equals(seller.getUniqueId())) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "You cannot fill your own order");
            return;
        }

        int playerHas = 0;
        for (ItemStack item : seller.getInventory().getContents()) {
            if (item != null && item.getType() == order.getMaterial()) {
                playerHas += item.getAmount();
            }
        }

        if (playerHas < amount) {
            confirmPurchase(purchaseId, false, BigDecimal.ZERO, "Not enough items in inventory");
            return;
        }

        plugin.getOrderManager().fillOrder(seller, orderId, amount);

        BigDecimal newBalance = plugin.getEconomyManager().getBalance(seller, order.getCurrency());
        BigDecimal totalPayout = order.getPricePerPiece().multiply(BigDecimal.valueOf(amount));
        String formatted = plugin.getEconomyManager().getFormattedWithSymbol(totalPayout, order.getCurrency());
        confirmPurchase(purchaseId, true, newBalance, formatted);
    }

    private void confirmPurchase(String purchaseId, boolean success, BigDecimal newBalance, String spent) {
        CompletableFuture.runAsync(() -> {
            try {
                StringBuilder json = new StringBuilder();
                json.append("{\"purchaseId\":\"").append(escJson(purchaseId)).append("\"")
                        .append(",\"serverId\":\"").append(escJson(serverId)).append("\"")
                        .append(",\"success\":").append(success)
                        .append(",\"newBalance\":").append(newBalance != null ? newBalance.doubleValue() : 0)
                        .append(",\"spent\":\"").append(escJson(spent)).append("\"}");
                postJson("/api/confirm-purchase", json.toString());
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
            String body = resp.body();
            if (resp.statusCode() == 503 && body.contains("\"queued\":true")) {
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

            if (resp.statusCode() == 403 && body.contains("Invalid server ID or API key")) {
                this.registered = false;
            }

            String snippet = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            throw new RuntimeException("HTTP " + resp.statusCode() + " (" + endpoint + "): " + snippet);
        }
        return resp.body();
    }

    /**
     * Escape JSON string values. Covers all required control characters.
     */
    private static String escJson(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    private List<Map<String, Object>> parsePendingPurchases(String jsonBody) {
        List<Map<String, Object>> result = new ArrayList<>();

        int idx = jsonBody.indexOf("\"pendingPurchases\"");
        if (idx < 0) return result;

        int arrStart = jsonBody.indexOf('[', idx);
        int arrEnd = jsonBody.indexOf(']', arrStart);
        if (arrStart < 0 || arrEnd < 0) return result;

        String arrStr = jsonBody.substring(arrStart + 1, arrEnd).trim();
        if (arrStr.isEmpty()) return result;

        String[] objects = arrStr.split("\\}\\s*,\\s*\\{");
        for (String obj : objects) {
            obj = obj.replace("{", "").replace("}", "").trim();
            Map<String, Object> map = new HashMap<>();

            String[] pairs = obj.split(",");
            for (String pair : pairs) {
                String[] kv = pair.split(":", 2);
                if (kv.length == 2) {
                    String key = kv[0].trim().replace("\"", "");
                    String value = kv[1].trim().replace("\"", "");

                    try {
                        if (value.contains(".")) map.put(key, Double.parseDouble(value));
                        else map.put(key, Integer.parseInt(value));
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

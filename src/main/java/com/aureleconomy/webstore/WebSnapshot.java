package com.aureleconomy.webstore;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Builds the JSON documents the web dashboard serves.
 *
 * <p>Field names and value formatting deliberately mirror what
 * {@code CloudSyncManager} posts to {@code /api/sync}, so the dashboard renders
 * identically whether it is fed by the local store or by the cloud backend.
 * Keep the two in sync when changing either.
 */
public class WebSnapshot {

    /** One published snapshot. All fields are complete JSON documents. */
    public static final class Snapshot {
        public final String categoriesJson;
        public final String itemsJson;
        public final String auctionsJson;
        public final String ordersJson;
        public final String stocksJson;
        public final String priceHistoryJson;
        public final String customItemsJson;
        public final boolean hasCustomItems;

        Snapshot(String categoriesJson, String itemsJson, String auctionsJson, String ordersJson,
                 String stocksJson, String priceHistoryJson, String customItemsJson,
                 boolean hasCustomItems) {
            this.categoriesJson = categoriesJson;
            this.itemsJson = itemsJson;
            this.auctionsJson = auctionsJson;
            this.ordersJson = ordersJson;
            this.stocksJson = stocksJson;
            this.priceHistoryJson = priceHistoryJson;
            this.customItemsJson = customItemsJson;
            this.hasCustomItems = hasCustomItems;
        }
    }

    private static final long PRICE_HISTORY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    /**
     * Points kept per item in the dashboard payload. The table accumulates one
     * row per item per recording interval for a week, so serialising all of it
     * would grow without bound; the chart cannot show more than this anyway.
     */
    private static final int MAX_POINTS_PER_ITEM = 240;

    /**
     * How long a serialised price-history document is reused before it is
     * rebuilt. New points only land when the recorder runs, so rebuilding on the
     * fast publish cadence is pure waste.
     */
    private static final long PRICE_HISTORY_CACHE_MS = 120_000L;

    private final AurelEconomy plugin;

    /**
     * Price history is the one expensive field, so it is rebuilt on its own slow
     * cadence and reused by the frequent snapshot publishes. {@code priceHistory}
     * only changes when {@link #recordPriceSnapshot()} writes, so recomputing it
     * every publish is wasted work that grows with the table.
     */
    private volatile String cachedPriceHistory = "{}";
    private volatile long priceHistoryBuiltAt = 0L;

    public WebSnapshot(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    /** Must run on the main thread: reads Bukkit material/player state. */
    public Snapshot capture() {
        return new Snapshot(buildCategories(), buildItems(), buildAuctions(),
                buildOrders(), buildStocks(), priceHistoryJson(), buildCustomItems(),
                hasCustomItems());
    }

    /** Invalidate the cached history so the next capture rebuilds it. */
    public void invalidatePriceHistory() {
        priceHistoryBuiltAt = 0L;
        dayAgoBuiltAt = 0L;
    }

    private boolean hasCustomItems() {
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        return registry != null && !registry.isEmpty();
    }

    // ── categories ────────────────────────────────────────────────────

    private String buildCategories() {
        StringBuilder json = new StringBuilder("[");
        Category[] cats = Category.values();
        for (int i = 0; i < cats.length; i++) {
            Category cat = cats[i];
            if (i > 0) {
                json.append(",");
            }
            json.append("{\"id\":\"").append(esc(cat.name())).append("\"");
            json.append(",\"name\":\"").append(esc(cat.name)).append("\"");
            json.append(",\"icon\":\"").append(esc(cat.icon.name().toLowerCase())).append("\"");
            json.append(",\"itemCount\":").append(MarketItems.getItems(cat).size());
            json.append("}");
        }
        return json.append("]").toString();
    }

    // ── items, grouped by category id ─────────────────────────────────

    private String buildItems() {
        StringBuilder json = new StringBuilder("{");
        Category[] cats = Category.values();
        for (int c = 0; c < cats.length; c++) {
            Category cat = cats[c];
            if (c > 0) {
                json.append(",");
            }
            json.append("\"").append(esc(cat.name())).append("\":[");

            List<MarketEntry> entries = MarketItems.getItems(cat).stream()
                    .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                    .toList();

            for (int i = 0; i < entries.size(); i++) {
                MarketEntry entry = entries.get(i);
                if (i > 0) {
                    json.append(",");
                }
                boolean customSpawner = entry.material == Material.SPAWNER && entry.customName != null;
                String key = customSpawner ? entry.customName : entry.material.name();
                String displayName = entry.customName != null ? entry.customName
                        : entry.material.name().replace("_", " ");
                BigDecimal buyPrice = customSpawner ? plugin.getMarketManager().getBuyPrice(entry.customName)
                        : plugin.getMarketManager().getBuyPrice(entry.material);
                String currency = customSpawner ? plugin.getMarketManager().getCurrency(entry.customName)
                        : plugin.getMarketManager().getCurrency(entry.material);

                json.append("{\"key\":\"").append(esc(key)).append("\"");
                json.append(",\"material\":\"").append(esc(entry.material.name().toLowerCase())).append("\"");
                json.append(",\"name\":\"").append(esc(displayName)).append("\"");
                json.append(",\"price\":").append(buyPrice.doubleValue());
                json.append(",\"priceFormatted\":\"")
                        .append(esc(plugin.getEconomyManager().getFormattedWithSymbol(buyPrice, currency)))
                        .append("\"");
                json.append(",\"currency\":\"").append(esc(currency)).append("\"");
                json.append(",\"currencySymbol\":\"")
                        .append(esc(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
                json.append("}");
            }
            json.append("]");
        }
        return json.append("}").toString();
    }

    // ── auctions ──────────────────────────────────────────────────────

    private String buildAuctions() {
        StringBuilder json = new StringBuilder("[");
        List<com.aureleconomy.auction.AuctionItem> auctions = plugin.getAuctionManager().getActiveAuctions();
        for (int i = 0; i < auctions.size(); i++) {
            com.aureleconomy.auction.AuctionItem ai = auctions.get(i);
            if (i > 0) {
                json.append(",");
            }
            String itemName = ai.getItem().getType().name().replace("_", " ");
            if (ai.getItem().hasItemMeta() && ai.getItem().getItemMeta().displayName() != null) {
                itemName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(ai.getItem().getItemMeta().displayName());
            }
            String bidderName = ai.getHighestBidder() != null
                    ? resolvePlayerName(ai.getHighestBidder()) : "";

            json.append("{\"id\":").append(ai.getId());
            json.append(",\"seller\":\"").append(esc(resolvePlayerName(ai.getSeller()))).append("\"");
            json.append(",\"sellerUuid\":\"").append(ai.getSeller()).append("\"");
            json.append(",\"itemName\":\"").append(esc(itemName)).append("\"");
            json.append(",\"material\":\"").append(esc(ai.getItem().getType().name().toLowerCase())).append("\"");
            json.append(",\"amount\":").append(ai.getItem().getAmount());
            json.append(",\"price\":").append(ai.getPrice());
            json.append(",\"currency\":\"").append(esc(ai.getCurrency())).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(esc(plugin.getEconomyManager().getCurrencySymbol(ai.getCurrency()))).append("\"");
            json.append(",\"isBin\":").append(ai.isBin());
            json.append(",\"purchaseMode\":\"").append(esc(ai.getPurchaseMode().name())).append("\"");
            json.append(",\"remaining\":").append(ai.getAvailableQuantity());
            json.append(",\"expiration\":").append(ai.getExpiration());
            json.append(",\"startTime\":").append(ai.getStartTime());
            json.append(",\"highestBidder\":\"").append(esc(bidderName)).append("\"");
            json.append("}");
        }
        return json.append("]").toString();
    }

    // ── buy orders ────────────────────────────────────────────────────

    private String buildOrders() {
        StringBuilder json = new StringBuilder("[");
        int i = 0;
        for (var order : plugin.getOrderManager().getActiveOrders()) {
            if (i++ > 0) {
                json.append(",");
            }
            json.append("{\"id\":").append(order.getId());
            json.append(",\"buyer\":\"").append(esc(resolvePlayerName(order.getBuyerUuid()))).append("\"");
            json.append(",\"buyerUuid\":\"").append(order.getBuyerUuid()).append("\"");
            json.append(",\"material\":\"").append(esc(order.getMaterial().name().toLowerCase())).append("\"");
            json.append(",\"itemName\":\"")
                    .append(esc(order.getMaterial().name().replace("_", " "))).append("\"");
            json.append(",\"amountRequested\":").append(order.getAmountRequested());
            json.append(",\"amountFilled\":").append(order.getAmountFilled());
            json.append(",\"pricePerPiece\":").append(order.getPricePerPiece());
            json.append(",\"currency\":\"").append(esc(order.getCurrency())).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(esc(plugin.getEconomyManager().getCurrencySymbol(order.getCurrency())))
                    .append("\"");
            json.append(",\"status\":\"").append(esc(order.getStatus())).append("\"");
            json.append("}");
        }
        return json.append("]").toString();
    }

    // ── stocks / price tracker ────────────────────────────────────────

        private String buildStocks() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        // One grouped query for the whole page instead of one per item.
        Map<String, long[]> volume = plugin.getTradeVolumeTracker().volumeSince(24L * 60 * 60 * 1000);
        Map<String, BigDecimal> dayAgoPrices = buyPrices24hAgo();
        for (MarketEntry entry : new ArrayList<>(plugin.getMarketManager().getEntryCache().values())) {
            if (plugin.getMarketManager().isBlacklisted(entry.material)) {
                continue;
            }
            if (!first) {
                json.append(",");
            }
            first = false;

            String priceKey = entry.customName != null ? entry.customName : entry.material.name();
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

            // A real 24h change: compare against the last recorded price at or
            // before the cutoff. Falls back to the listing price for items with
            // no history that old, and says so in changeBasis so the dashboard
            // can label it honestly.
            BigDecimal dayAgo = dayAgoPrices.get(priceKey);
            boolean haveDayAgo = dayAgo != null && dayAgo.compareTo(BigDecimal.ZERO) > 0;
            BigDecimal reference = haveDayAgo ? dayAgo : basePrice;
            boolean comparable = haveDayAgo
                    || (basePrice.compareTo(BigDecimal.ZERO) > 0 && basePrice.compareTo(BigDecimal.ONE) != 0);

            BigDecimal change = BigDecimal.ZERO;
            if (comparable && buyPrice.compareTo(BigDecimal.ZERO) > 0) {
                change = buyPrice.subtract(reference).multiply(BigDecimal.valueOf(100))
                        .divide(reference, 4, RoundingMode.HALF_UP);
            }
            String changeBasis = haveDayAgo ? "24h" : "listing";

            String currency = (entry.material == Material.SPAWNER && entry.customName != null)
                    ? plugin.getMarketManager().getCurrency(entry.customName)
                    : plugin.getMarketManager().getCurrency(entry.material);

            json.append("{\"key\":\"").append(esc(priceKey)).append("\"");
            json.append(",\"material\":\"").append(esc(entry.material.name().toLowerCase())).append("\"");
            json.append(",\"name\":\"").append(esc(displayName)).append("\"");
            json.append(",\"buyPrice\":").append(buyPrice.doubleValue());
            json.append(",\"sellPrice\":").append(sellPrice.doubleValue());
            json.append(",\"change\":").append(change.doubleValue());
            json.append(",\"changeBasis\":\"").append(changeBasis).append("\"");
            long[] vol = volume.get(priceKey);
            json.append(",\"volume\":").append(vol == null ? 0 : vol[0]);
            json.append(",\"trades\":").append(vol == null ? 0 : vol[1]);
            json.append(",\"currency\":\"").append(esc(currency)).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(esc(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
            json.append("}");
        }
        return json.append("]").toString();
    }

    // ── custom items ──────────────────────────────────────────────────

    private String buildCustomItems() {
        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (registry == null || registry.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        int i = 0;
        for (CustomMarketItem cmi : registry.getAllItems()) {
            if (i++ > 0) {
                json.append(",");
            }
            String name = cmi.getDisplayName() != null ? cmi.getDisplayName() : cmi.getCanonicalId();
            String currency = plugin.getEconomyManager().getDefaultCurrency();
            json.append("{\"id\":\"").append(esc(cmi.getCanonicalId())).append("\"");
            json.append(",\"name\":\"").append(esc(name)).append("\"");
            json.append(",\"material\":\"").append(esc(cmi.getItemStack().getType().name().toLowerCase()))
                    .append("\"");
            json.append(",\"buyPrice\":").append(cmi.getBuyPrice().doubleValue());
            json.append(",\"sellPrice\":").append(cmi.getSellPrice().doubleValue());
            json.append(",\"currency\":\"").append(esc(currency)).append("\"");
            json.append(",\"currencySymbol\":\"")
                    .append(esc(plugin.getEconomyManager().getCurrencySymbol(currency))).append("\"");
            json.append("}");
        }
        return json.append("]").toString();
    }

    // ── price history ─────────────────────────────────────────────────

    /** How far back the stocks page's change column looks. */
    private static final long CHANGE_WINDOW_MS = 24L * 60 * 60 * 1000;

    private volatile Map<String, BigDecimal> dayAgoPrices = new LinkedHashMap<>();
    private volatile long dayAgoBuiltAt = 0L;

    /**
     * Buy price per item as of roughly 24 hours ago, for the stocks page's change
     * column.
     * <p>
     * Two queries rather than one correlated join: find the newest snapshot at or
     * before the cutoff, then read that snapshot. Every item is written in a
     * single batch per snapshot, so a whole snapshot shares one timestamp and
     * this returns a consistent set. Both queries are served by
     * {@code idx_price_history_timestamp}.
     * <p>
     * Cached on the same cadence as the price history JSON, since it comes from
     * the same table and cannot change between snapshots.
     */
    private Map<String, BigDecimal> buyPrices24hAgo() {
        long now = System.currentTimeMillis();
        if (dayAgoBuiltAt > 0 && now - dayAgoBuiltAt < PRICE_HISTORY_CACHE_MS) {
            return dayAgoPrices;
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        long cutoff = now - CHANGE_WINDOW_MS;
        try (var conn = plugin.getDatabaseManager().getConnection()) {
            long at = -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT MAX(timestamp) FROM price_history WHERE timestamp <= ?")) {
                ps.setLong(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        at = rs.getLong(1);
                    }
                }
            }
            if (at > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT item_key, buy_price FROM price_history WHERE timestamp = ?")) {
                    ps.setLong(1, at);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString(1), rs.getBigDecimal(2));
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getComponentLogger().warn("24h price lookup failed: " + e.getMessage());
        }
        dayAgoPrices = out;
        dayAgoBuiltAt = now;
        return out;
    }

    /**
     * Cached accessor: rebuilds at most once per {@code maxAgeMs}.
     * Serialising a week of snapshots is the only part of a publish that scales
     * with the size of the table rather than with the size of the economy.
     */
    private String priceHistoryJson() {
        long now = System.currentTimeMillis();
        if (now - priceHistoryBuiltAt < PRICE_HISTORY_CACHE_MS) {
            return cachedPriceHistory;
        }
        cachedPriceHistory = loadPriceHistoryJson();
        priceHistoryBuiltAt = now;
        return cachedPriceHistory;
    }

    /**
     * Appends one price point per tradeable item and prunes anything older than
     * the retention window.
     *
     * <p>Needed by the dashboard in local mode, where nothing else records it.
     * Safe to call from an async task: it only touches the database.
     */
    public void recordPriceSnapshot() {
        long now = System.currentTimeMillis();
        List<MarketEntry> items = MarketItems.getItems(Category.ALL_ITEMS).stream()
                .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                .toList();

        try (var conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO price_history (item_key, buy_price, sell_price, timestamp)"
                             + " VALUES (?, ?, ?, ?)")) {
            for (MarketEntry entry : items) {
                String key = entry.customName != null ? entry.customName : entry.material.name();
                BigDecimal buy = plugin.getMarketManager().getBuyPrice(key);
                BigDecimal sell = plugin.getMarketManager().getSellPrice(key);
                if (entry.price.compareTo(BigDecimal.ONE) == 0 && buy.compareTo(BigDecimal.ONE) == 0) {
                    continue;
                }
                ps.setString(1, key);
                ps.setBigDecimal(2, buy);
                ps.setBigDecimal(3, sell);
                ps.setLong(4, now);
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Price history record failed: " + e.getMessage());
        }

        try (var conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM price_history WHERE timestamp < ?")) {
            ps.setLong(1, now - PRICE_HISTORY_WINDOW_MS);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Failed to prune old price history: " + e.getMessage());
        }
        invalidatePriceHistory();
    }

    private String loadPriceHistoryJson() {
        StringBuilder sb = new StringBuilder("{");
        long cutoff = System.currentTimeMillis() - PRICE_HISTORY_WINDOW_MS;
        try (var conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT item_key, buy_price, sell_price, timestamp FROM price_history "
                             + "WHERE timestamp > ? ORDER BY item_key ASC, timestamp ASC")) {
            ps.setLong(1, cutoff);
            ResultSet rs = ps.executeQuery();

            Map<String, List<String>> grouped = new LinkedHashMap<>();
            List<String> order = new ArrayList<>();
            while (rs.next()) {
                String key = rs.getString("item_key");
                String entry = "{\"t\":" + rs.getLong("timestamp")
                        + ",\"b\":" + rs.getDouble("buy_price")
                        + ",\"s\":" + rs.getDouble("sell_price")
                        + "}";
                List<String> bucket = grouped.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<>();
                    grouped.put(key, bucket);
                    order.add(key);
                }
                bucket.add(entry);
            }

            int i = 0;
            for (String key : order) {
                if (i++ > 0) {
                    sb.append(",");
                }
                sb.append("\"").append(esc(key)).append("\":[")
                        .append(String.join(",", downsample(grouped.get(key)))).append("]");
            }
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Web snapshot: price history load failed: " + e.getMessage());
        }
        return sb.append("}").toString();
    }

    /**
     * Evenly thins a series to {@link #MAX_POINTS_PER_ITEM} entries, always
     * keeping the first and last so the chart's endpoints stay accurate.
     */
    static List<String> downsample(List<String> points) {
        if (points.size() <= MAX_POINTS_PER_ITEM) {
            return points;
        }
        List<String> out = new ArrayList<>(MAX_POINTS_PER_ITEM);
        double step = (points.size() - 1.0) / (MAX_POINTS_PER_ITEM - 1.0);
        for (int i = 0; i < MAX_POINTS_PER_ITEM; i++) {
            out.add(points.get((int) Math.round(i * step)));
        }
        return out;
    }

    private String resolvePlayerName(java.util.UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        return off.getName() != null ? off.getName() : uuid.toString().substring(0, 8);
    }

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.toString();
    }
}

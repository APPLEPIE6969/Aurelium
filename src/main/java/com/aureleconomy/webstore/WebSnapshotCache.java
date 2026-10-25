package com.aureleconomy.webstore;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * In-memory view of the economy for the dashboard.
 *
 * <p>Intentionally not persistent and not configurable. Every field is derived
 * from the plugin's own database, so a copy on disk would add a second thing to
 * keep in sync and a staleness window while storing no information that cannot
 * be rebuilt in milliseconds at boot. There is nothing here to choose between.
 */
public class WebSnapshotCache {

    private volatile String categories = "[]";
    private volatile String items = "{}";
    private volatile String auctions = "[]";
    private volatile String orders = "[]";
    private volatile String stocks = "[]";
    private volatile String priceHistory = "{}";
    private volatile String customItems = "[]";
    private volatile boolean hasCustomItems = false;
    private volatile long publishedAt = 0;

    // Parsed views of the three collections the API pages over. Auctions, orders
    // and stocks each run to thousands of rows, so the handler needs them as
    // arrays to search, sort and slice; parsing once per publish keeps that off
    // the request path.
    private volatile JsonArray auctionsArray;
    private volatile JsonArray ordersArray;
    private volatile JsonArray stocksArray;
    // The item catalogue is a category -> array object rather than a flat array,
    // and it is the largest document in the snapshot. It used to be parsed on
    // every /items and /search request, which is what the API rate limit now
    // protects; parsing it once per publish removes the cost those requests were
    // paying even when they were legitimate.
    private volatile JsonObject itemsObject;

    /** Replaces every document at once so a reader never sees a half-updated view. */
    public void publish(WebSnapshot.Snapshot s) {
        this.categories = s.categoriesJson;
        this.items = s.itemsJson;
        this.auctions = s.auctionsJson;
        this.orders = s.ordersJson;
        this.stocks = s.stocksJson;
        this.priceHistory = s.priceHistoryJson;
        this.customItems = s.customItemsJson;
        this.hasCustomItems = s.hasCustomItems;
        this.publishedAt = System.currentTimeMillis();
        // The parsed views describe the previous publish, so drop them.
        this.auctionsArray = null;
        this.ordersArray = null;
        this.stocksArray = null;
        this.itemsObject = null;
    }

    public JsonArray auctionsArray() {
        JsonArray local = auctionsArray;
        if (local == null) {
            local = parseArray(auctions);
            auctionsArray = local;
        }
        return local;
    }

    public JsonArray ordersArray() {
        JsonArray local = ordersArray;
        if (local == null) {
            local = parseArray(orders);
            ordersArray = local;
        }
        return local;
    }

    public JsonArray stocksArray() {
        JsonArray local = stocksArray;
        if (local == null) {
            local = parseArray(stocks);
            stocksArray = local;
        }
        return local;
    }

    private static JsonArray parseArray(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    /** Parsed item catalogue, cached until the next publish. Never null. */
    public JsonObject itemsObject() {
        JsonObject local = itemsObject;
        if (local == null) {
            local = parseObject(items);
            itemsObject = local;
        }
        return local;
    }

    private static JsonObject parseObject(String json) {
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    public String categoriesJson() {
        return categories;
    }

    public String itemsJson() {
        return items;
    }

    public String auctionsJson() {
        return auctions;
    }

    public String ordersJson() {
        return orders;
    }

    public String stocksJson() {
        return stocks;
    }

    public String priceHistoryJson() {
        return priceHistory;
    }

    public String customItemsJson() {
        return customItems;
    }

    public boolean hasCustomItems() {
        return hasCustomItems;
    }

    public long publishedAt() {
        return publishedAt;
    }
}

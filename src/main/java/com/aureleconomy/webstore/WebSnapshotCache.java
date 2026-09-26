package com.aureleconomy.webstore;

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

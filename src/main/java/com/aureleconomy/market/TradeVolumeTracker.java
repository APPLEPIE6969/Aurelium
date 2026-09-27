package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;

/**
 * Records completed market trades so the dashboard can show real traded volume
 * instead of a placeholder.
 * <p>
 * This is a log, not a counter: {@link #volumeSince} counts the rows inside a
 * time window, which keeps both the 24h and 7-day figures exact and needs no
 * rollover bookkeeping. Rows older than the retention window are pruned on a
 * timer by the caller.
 * <p>
 * Only market trades are recorded here. Auctions and buy orders settle against
 * their own price systems and are deliberately excluded, so the volume figure
 * lines up with the market prices shown on the stocks page.
 */
public class TradeVolumeTracker {

    /** How long a trade stays in the log. Bounds the table and the queries. */
    public static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;

    private final AurelEconomy plugin;

    public TradeVolumeTracker(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Log one completed trade. {@code isBuy} is true when a player bought from
     * the market and false when they sold into it.
     * <p>
     * Never throws: a failure to log volume must not roll back a trade that has
     * already moved money and items.
     */
    public void record(String itemKey, int amount, boolean isBuy) {
        if (itemKey == null || amount <= 0) {
            return;
        }
        try (var conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO trade_volume (item_key, amount, is_buy, trade_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, itemKey);
            ps.setInt(2, amount);
            ps.setInt(3, isBuy ? 1 : 0);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Trade volume record failed: " + e.getMessage());
        }
    }

    /**
     * Traded units per item key since {@code windowMs} ago, as
     * {@code key -> [units, trades]}. The second element is the number of
     * individual trades, which the dashboard shows as a secondary hint.
     */
    public Map<String, long[]> volumeSince(long windowMs) {
        long cutoff = System.currentTimeMillis() - windowMs;
        Map<String, long[]> out = new HashMap<>();
        try (var conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT item_key, SUM(amount), COUNT(*) FROM trade_volume"
                             + " WHERE trade_at >= ? GROUP BY item_key")) {
            ps.setLong(1, cutoff);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3)});
                }
            }
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Trade volume query failed: " + e.getMessage());
        }
        return out;
    }

    /** Units traded for one key in the last 24 hours, or 0. */
    public long volume24h(String itemKey) {
        long[] v = volumeSince(24L * 60 * 60 * 1000).get(itemKey);
        return v == null ? 0 : v[0];
    }

    /** Trades recorded for one key in the last 24 hours, or 0. */
    public long trades24h(String itemKey) {
        long[] v = volumeSince(24L * 60 * 60 * 1000).get(itemKey);
        return v == null ? 0 : v[1];
    }

    /** Drop rows past the retention window. Safe to call off the main thread. */
    public void prune() {
        try (var conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM trade_volume WHERE trade_at < ?")) {
            ps.setLong(1, System.currentTimeMillis() - RETENTION_MS);
            ps.executeUpdate();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("Failed to prune trade volume: " + e.getMessage());
        }
    }
}

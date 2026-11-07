package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The one insert into {@code offline_earnings}, shared by the auction and buy
 * order paths.
 *
 * <p>Both paths wrote the same row and both left {@code currency} to its NULL
 * default, so the join message formatted the amount with the default currency's
 * symbol whatever the earning had actually been paid in (M14). Keeping the
 * column list in one place is what makes "write the currency" true for every
 * caller rather than for whichever one was remembered.
 */
public final class OfflineEarnings {

    private OfflineEarnings() {
    }

    /**
     * Records one earning or delivery notification. Synchronous on purpose:
     * callers hold a scheduler and run this off the main thread.
     */
    public static void record(AurelEconomy plugin, UUID uuid, BigDecimal amount,
                              String currency, String itemDisplay) {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            if (conn == null) {
                return; // getConnection() has already logged why.
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO offline_earnings (uuid, amount, currency, item_display, timestamp)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setBigDecimal(2, amount);
                ps.setString(3, currency);
                ps.setString(4, itemDisplay);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Database error logging offline earning", e);
        }
    }
}

package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * H9: the database record of every dashboard purchase this server has claimed.
 *
 * <p>The dedup used to be a {@code ConcurrentHashMap} purged after ten minutes
 * and rebuilt empty on every restart, so the same purchase ID could be charged
 * twice: once before the restart and once after. The claim row is written
 * before the purchase executes, which is what makes the answer survive the JVM.
 *
 * <p>A failed claim is a refusal, whatever the cause. Executing on an uncertain
 * claim is exactly the double-charge this table exists to stop, so a database
 * error refuses the purchase instead of falling back to the old map.
 *
 * <p>A row left in CLAIMED by a crash blocks that purchase ID forever. That is
 * deliberate: at-most-once is the only safe side when the alternative is
 * charging a player twice.
 */
public class ProcessedPurchaseStore {

    /** Confirmed rows are kept this long, then pruned. READY and CLAIMED rows are not. */
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    /** How many unconfirmed rows a single startup retries. */
    private static final int RECONCILE_LIMIT = 100;

    public enum Status {
        CLAIMED,
        READY,
        CONFIRMED
    }

    /** A purchase whose outcome still has to reach the dashboard. */
    public record Confirmation(String purchaseId, boolean success,
            BigDecimal newBalance, String spent) {
    }

    private final AurelEconomy plugin;

    public ProcessedPurchaseStore(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * The dedup. Returns true exactly once per purchase ID, ever, for this
     * database. A replay fails the primary key and returns false.
     */
    public boolean claim(String purchaseId) {
        if (purchaseId == null || purchaseId.isBlank()) {
            return false;
        }
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                return false;
            }
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO processed_purchases "
                                    + "(purchase_id, status, success, new_balance, spent, processed_at) "
                                    + "VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, purchaseId);
                ps.setString(2, Status.CLAIMED.name());
                ps.setBoolean(3, false);
                ps.setBigDecimal(4, BigDecimal.ZERO);
                ps.setString(5, "");
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                if (isIntegrityViolation(e)) {
                    return false;
                }
                plugin.getComponentLogger().error("Could not claim web purchase " + purchaseId
                        + "; it will not be executed", e);
                return false;
            }
        }
    }

    /**
     * Records the outcome so a failed confirmation can be retried verbatim.
     * Returns true only for the first outcome recorded for this purchase: a
     * later failure path after the sale already succeeded must not overwrite
     * it or tell the dashboard a second, contradictory result.
     */
    public boolean markReady(String purchaseId, boolean success, BigDecimal newBalance, String spent) {
        if (purchaseId == null || purchaseId.isBlank()) {
            return false;
        }
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                return false;
            }
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(
                            "UPDATE processed_purchases SET status = ?, success = ?, "
                                    + "new_balance = ?, spent = ? WHERE purchase_id = ? AND status = ?")) {
                ps.setString(1, Status.READY.name());
                ps.setBoolean(2, success);
                ps.setBigDecimal(3, newBalance == null ? BigDecimal.ZERO : newBalance);
                ps.setString(4, spent == null ? "" : spent);
                ps.setString(5, purchaseId);
                ps.setString(6, Status.CLAIMED.name());
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                plugin.getComponentLogger().warn(
                        "Could not record the outcome of web purchase " + purchaseId + ": " + e.getMessage());
                return false;
            }
        }
    }

    /** Marks a READY row acknowledged by the dashboard. */
    public void markConfirmed(String purchaseId) {
        if (purchaseId == null || purchaseId.isBlank()) {
            return;
        }
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                return;
            }
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(
                            "UPDATE processed_purchases SET status = ? "
                                    + "WHERE purchase_id = ? AND status = ?")) {
                ps.setString(1, Status.CONFIRMED.name());
                ps.setString(2, purchaseId);
                ps.setString(3, Status.READY.name());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().warn(
                        "Could not mark web purchase " + purchaseId + " confirmed: " + e.getMessage());
            }
        }
    }

    /** READY rows, oldest first: the startup reconciliation work list. */
    public List<Confirmation> pendingConfirmations() {
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                return Collections.emptyList();
            }
            List<Confirmation> pending = new ArrayList<>();
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT purchase_id, success, new_balance, spent FROM processed_purchases "
                                    + "WHERE status = ? ORDER BY processed_at ASC LIMIT " + RECONCILE_LIMIT)) {
                ps.setString(1, Status.READY.name());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pending.add(new Confirmation(rs.getString("purchase_id"),
                                rs.getBoolean("success"),
                                rs.getBigDecimal("new_balance"),
                                rs.getString("spent")));
                    }
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().warn("Could not read unconfirmed web purchases: " + e.getMessage());
                return Collections.emptyList();
            }
            return pending;
        }
    }

    /** Drops confirmed rows past the retention window. Returns how many were removed. */
    public int prune() {
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                return 0;
            }
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(
                            "DELETE FROM processed_purchases WHERE status = ? AND processed_at < ?")) {
                ps.setString(1, Status.CONFIRMED.name());
                ps.setLong(2, System.currentTimeMillis() - RETENTION_MS);
                return ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().warn("Could not prune processed web purchases: " + e.getMessage());
                return 0;
            }
        }
    }

    /**
     * A duplicate purchase ID is the normal replay path, not an error. Both
     * SQLite and MySQL report it as an integrity-constraint violation
     * (SQLSTATE class 23); the message check covers drivers that leave the
     * state unset.
     */
    private static boolean isIntegrityViolation(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String message = e.getMessage();
        return message != null
                && (message.contains("UNIQUE") || message.contains("Duplicate")
                        || message.contains("PRIMARY KEY"));
    }
}

package com.aureleconomy.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Deletes rows and files past their retention window (L4).
 *
 * <p>Three things grew without bound: ended auctions stayed in the table after
 * both sides had collected, so the seller's and winner's finished listings
 * accumulated forever; offline earning notifications for players who never
 * came back were never removed; and every plugin version change left a
 * database backup file behind. The purges only ever remove what is finished
 * with, never an auction somebody can still claim.
 */
public final class RetentionPolicy {

    /** Days kept when the config does not say. */
    public static final int DEFAULT_DAYS = 30;

    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private RetentionPolicy() {
    }

    /**
     * The cutoff for a window in days, or 0 when the window is disabled. A
     * window of 0 (or less) keeps that kind forever, which is the behaviour
     * before this existed and the escape hatch if an owner wants it back.
     */
    public static long cutoff(int days, long now) {
        return days <= 0 ? 0L : now - days * MILLIS_PER_DAY;
    }

    /**
     * Removes ended auctions that are collected and older than the window, and
     * the offers that belong to them. Returns the number of auctions removed.
     *
     * <p>Offers go first on purpose: {@code auction_offers} has a foreign key to
     * {@code auctions}, MySQL enforces it, and deleting the parent first would
     * fail. {@code collected = 1} is the eligibility rule: an ended auction
     * nobody has collected still holds an item or a payout for someone.
     */
    public static int purgeAuctions(Connection conn, long cutoffMillis) throws SQLException {
        if (cutoffMillis <= 0) {
            return 0;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM auction_offers WHERE auction_id IN (SELECT id FROM auctions"
                        + " WHERE ended = 1 AND collected = 1 AND expiration < ?)")) {
            ps.setLong(1, cutoffMillis);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM auctions WHERE ended = 1 AND collected = 1 AND expiration < ?")) {
            ps.setLong(1, cutoffMillis);
            return ps.executeUpdate();
        }
    }

    /**
     * Removes offline earning notifications older than the window. The money
     * was paid when the earning was recorded; the row is only the line the
     * player is shown on their next join.
     */
    public static int purgeOfflineEarnings(Connection conn, long cutoffMillis) throws SQLException {
        if (cutoffMillis <= 0) {
            return 0;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM offline_earnings WHERE timestamp < ?")) {
            ps.setLong(1, cutoffMillis);
            return ps.executeUpdate();
        }
    }

    /**
     * Removes database backups older than the window. Only the files
     * {@link DatabaseManager#backupDatabase(String)} creates are touched: the
     * name has to be a {@code database_v*.db}, so an owner's own copy in the
     * same folder is left alone.
     */
    public static int purgeBackupFiles(File backupDir, long cutoffMillis) {
        if (cutoffMillis <= 0 || backupDir == null || !backupDir.isDirectory()) {
            return 0;
        }
        File[] files = backupDir.listFiles();
        if (files == null) {
            return 0;
        }
        int removed = 0;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith("database_v") && name.endsWith(".db")
                    && file.lastModified() < cutoffMillis && file.delete()) {
                removed++;
            }
        }
        return removed;
    }
}

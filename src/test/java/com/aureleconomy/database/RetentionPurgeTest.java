package com.aureleconomy.database;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the retention job is allowed to delete (L4).
 *
 * <p>{@code auctions}, {@code offline_earnings} and the plugin's own database
 * backups grew forever. The purge may remove an auction only when it is over,
 * collected by both sides and past the window: an ended auction nobody has
 * collected still holds an item or a payout. It also has to leave the offers of
 * a kept auction alone, and an owner's own files in the backups folder.
 */
class RetentionPurgeTest {

    private static final long DAY = 24L * 60L * 60L * 1000L;

    private static final UUID SELLER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WINNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir
    Path tempDir;

    private File dataFolder;
    private File backupFolder;
    private YamlConfiguration config;
    private DatabaseManager manager;

    @BeforeEach
    void setUp() {
        dataFolder = tempDir.resolve("retention").toFile();
        assertTrue(dataFolder.mkdirs());
        backupFolder = new File(dataFolder, "backups");
        assertTrue(backupFolder.mkdirs());

        config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");

        AurelEconomy plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "the test database must initialize");
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("only ended, collected auctions past the window are purged, with their offers")
    void purgeRemovesOnlyEligibleRows() throws Exception {
        long now = System.currentTimeMillis();
        long oldExpiration = now - 45L * DAY;
        long recentExpiration = now - 20L * DAY;

        long finished = insertAuction(true, true, oldExpiration);
        long uncollected = insertAuction(true, false, oldExpiration);
        long active = insertAuction(false, false, oldExpiration);
        // Collected before it ended is an impossible row: it would mean the item
        // was handed over while someone could still bid. The purge keeps it
        // rather than guessing which half of the state is the true one.
        long collectedButNotEnded = insertAuction(false, true, oldExpiration);
        long recent = insertAuction(true, true, recentExpiration);

        insertOffer(finished, oldExpiration);
        insertOffer(uncollected, oldExpiration);
        insertOffer(recent, recentExpiration);

        insertEarning(oldExpiration);
        insertEarning(recentExpiration);

        File oldBackup = backup("database_v7_" + oldExpiration + ".db", oldExpiration);
        File recentBackup = backup("database_v7_" + recentExpiration + ".db", recentExpiration);
        File notes = backup("notes.txt", oldExpiration);

        int removed = manager.purgeRetention();

        assertEquals(Set.of(uncollected, active, collectedButNotEnded, recent), auctionIds(),
                "an uncollected auction still owes someone an item, and an active one is not over");
        assertEquals(2, offerCount(),
                "offers on a kept auction stay, and an offer on a purged auction must not be orphaned");
        assertEquals(1, earningCount(),
                "only the notification past the window may go; the money was already paid");
        assertFalse(oldBackup.exists(), "the old backup is what this job is for");
        assertTrue(recentBackup.exists(), "a backup inside the window is still a backup");
        assertTrue(notes.exists(), "an owner's own file in the backups folder is not ours to delete");
        assertEquals(3, removed, "one auction, one earning and one backup file were removed");
    }

    @Test
    @DisplayName("a window of zero keeps that kind of data forever")
    void zeroWindowKeepsEverything() throws Exception {
        config.set("database.retention.auctions-days", 0);
        config.set("database.retention.offline-earnings-days", 0);
        config.set("database.retention.backups-days", 0);

        long ancient = System.currentTimeMillis() - 3650L * DAY;
        long auction = insertAuction(true, true, ancient);
        insertOffer(auction, ancient);
        insertEarning(ancient);
        File backup = backup("database_v1_" + ancient + ".db", ancient);

        assertEquals(0, manager.purgeRetention(), "zero disables the window");
        assertEquals(Set.of(auction), auctionIds());
        assertEquals(1, offerCount());
        assertEquals(1, earningCount());
        assertTrue(backup.exists(), "a disabled window keeps every file");
    }

    @Test
    @DisplayName("the cutoff is whole days before now, and the default is 30")
    void cutoffIsWholeDays() {
        long now = 1_700_000_000_000L;

        assertEquals(now - 30L * DAY, RetentionPolicy.cutoff(30, now));
        assertEquals(now - 30L * DAY, RetentionPolicy.cutoff(RetentionPolicy.DEFAULT_DAYS, now),
                "the shipped default is 30 days");
        assertEquals(0L, RetentionPolicy.cutoff(0, now), "zero means no cutoff");
        assertEquals(0L, RetentionPolicy.cutoff(-1, now), "a negative window cannot mean delete everything");
    }

    private long insertAuction(boolean ended, boolean collected, long expiration) throws Exception {
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO auctions (seller_uuid, item_data, price, currency, is_bin,"
                             + " expiration, highest_bidder_uuid, ended, collected, listing_fee,"
                             + " start_time, purchase_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, SELLER.toString());
            ps.setString(2, "item");
            ps.setBigDecimal(3, new BigDecimal("100.00"));
            ps.setString(4, "Aurels");
            ps.setBoolean(5, true);
            ps.setLong(6, expiration);
            ps.setString(7, WINNER.toString());
            ps.setBoolean(8, ended);
            ps.setBoolean(9, collected);
            ps.setBigDecimal(10, BigDecimal.ZERO);
            ps.setLong(11, expiration - DAY);
            ps.setString(12, "STACK");
            ps.executeUpdate();
        }
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT MAX(id) FROM auctions");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "the inserted auction has no id");
            return rs.getLong(1);
        }
    }

    private void insertOffer(long auctionId, long timestamp) throws Exception {
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO auction_offers (auction_id, bidder_uuid, amount, currency,"
                             + " status, timestamp) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setLong(1, auctionId);
            ps.setString(2, WINNER.toString());
            ps.setBigDecimal(3, new BigDecimal("110.00"));
            ps.setString(4, "Aurels");
            ps.setString(5, "OUTBID");
            ps.setLong(6, timestamp);
            ps.executeUpdate();
        }
    }

    private void insertEarning(long timestamp) throws Exception {
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO offline_earnings (uuid, amount, currency, item_display, timestamp)"
                             + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, SELLER.toString());
            ps.setBigDecimal(2, new BigDecimal("100.00"));
            ps.setString(3, "Aurels");
            ps.setString(4, "Diamond (x1)");
            ps.setLong(5, timestamp);
            ps.executeUpdate();
        }
    }

    private File backup(String name, long lastModified) throws Exception {
        File file = new File(backupFolder, name);
        Files.write(file.toPath(), new byte[] {1});
        assertTrue(file.setLastModified(lastModified), "could not backdate " + name);
        return file;
    }

    private Set<Long> auctionIds() throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM auctions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("id"));
            }
        }
        return ids;
    }

    private long offerCount() throws Exception {
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM auction_offers");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private long earningCount() throws Exception {
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM offline_earnings");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }
}

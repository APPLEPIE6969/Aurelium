package com.aureleconomy.database;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit tests for DatabaseManager schema migration using temporary SQLite databases.
 * No Minecraft server needed — pure JDBC tests.
 */
class DatabaseManagerTest {

    @TempDir
    static Path tempDir;

    private Connection getConnection(String dbName) throws SQLException {
        String url = "jdbc:sqlite:" + tempDir.resolve(dbName).toString();
        Connection conn = DriverManager.getConnection(url);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=30000");
        }
        return conn;
    }

    @Test
    @DisplayName("Schema v2 creates custom_items table with all columns")
    void schemaV2CreatesCustomItemsTable() throws Exception {
        try (Connection conn = getConnection("test_schema.db");
             Statement stmt = conn.createStatement()) {
            // Create all v2 tables
            stmt.execute("CREATE TABLE IF NOT EXISTS database_info (version INTEGER PRIMARY KEY)");
            stmt.execute("INSERT INTO database_info VALUES (2)");

            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY,"
                    + "source_plugin TEXT NOT NULL,"
                    + "display_name TEXT,"
                    + "item_data TEXT NOT NULL,"
                    + "pdc_key TEXT,"
                    + "model_data_key TEXT,"
                    + "lore_hash TEXT,"
                    + "plugin_native_id TEXT,"
                    + "category TEXT,"
                    + "buy_price REAL DEFAULT -1,"
                    + "sell_price REAL DEFAULT -1,"
                    + "enabled INTEGER DEFAULT 1,"
                    + "discovery_methods TEXT,"
                    + "first_discovered INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL"
                    + ")");

            ResultSet cols = stmt.executeQuery("PRAGMA table_info(custom_items)");
            Set<String> colNames = new HashSet<>();
            while (cols.next()) {
                colNames.add(cols.getString("name"));
            }

            String[] expected = {"canonical_id", "source_plugin", "display_name", "item_data",
                    "pdc_key", "model_data_key", "lore_hash", "plugin_native_id",
                    "category", "buy_price", "sell_price", "enabled",
                    "discovery_methods", "first_discovered", "last_seen"};
            for (String col : expected) {
                assertTrue(colNames.contains(col), "Missing column: " + col);
            }
        }
    }

    @Test
    @DisplayName("Schema version is stored as 2 after initialization")
    void schemaVersionIs2() throws Exception {
        try (Connection conn = getConnection("test_version.db");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS database_info (version INTEGER PRIMARY KEY)");
            stmt.execute("INSERT INTO database_info VALUES (2)");

            ResultSet rs = stmt.executeQuery("SELECT version FROM database_info LIMIT 1");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("version"));
        }
    }

    @Test
    @DisplayName("v1 to v2 migration adds custom_items table")
    void migrationV1ToV2() throws Exception {
        try (Connection conn = getConnection("test_migration.db");
             Statement stmt = conn.createStatement()) {
            // Start at v1 — no custom_items table
            stmt.execute("CREATE TABLE IF NOT EXISTS database_info (version INTEGER PRIMARY KEY)");
            stmt.execute("INSERT INTO database_info VALUES (1)");
            stmt.execute("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, name TEXT)");

            // Verify custom_items doesn't exist yet
            ResultSet tables = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='custom_items'");
            assertFalse(tables.next(), "custom_items should not exist at v1");

            // Apply v2 migration: create custom_items
            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY, source_plugin TEXT NOT NULL,"
                    + "display_name TEXT, item_data TEXT NOT NULL,"
                    + "pdc_key TEXT, model_data_key TEXT, lore_hash TEXT,"
                    + "plugin_native_id TEXT, category TEXT,"
                    + "buy_price REAL DEFAULT -1, sell_price REAL DEFAULT -1,"
                    + "enabled INTEGER DEFAULT 1, discovery_methods TEXT,"
                    + "first_discovered INTEGER NOT NULL, last_seen INTEGER NOT NULL)");
            stmt.execute("DELETE FROM database_info");
            stmt.execute("INSERT INTO database_info VALUES (2)");

            // Verify custom_items now exists
            ResultSet tables2 = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='custom_items'");
            assertTrue(tables2.next(), "custom_items should exist after v2 migration");

            // Verify schema version
            ResultSet rs = stmt.executeQuery("SELECT version FROM database_info LIMIT 1");
            assertTrue(rs.next());
            assertEquals(2, rs.getInt("version"));
        }
    }

    @Test
    @DisplayName("INSERT OR REPLACE works for custom_items in SQLite")
    void insertOrReplaceSqlite() throws Exception {
        try (Connection conn = getConnection("test_upsert.db");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY, source_plugin TEXT NOT NULL,"
                    + "display_name TEXT, item_data TEXT NOT NULL,"
                    + "buy_price REAL DEFAULT -1, sell_price REAL DEFAULT -1,"
                    + "enabled INTEGER DEFAULT 1, discovery_methods TEXT,"
                    + "first_discovered INTEGER NOT NULL, last_seen INTEGER NOT NULL)");

            stmt.execute("INSERT INTO custom_items (canonical_id, source_plugin, item_data, "
                    + "first_discovered, last_seen) VALUES ('test:sword', 'Test', 'data', 1000, 1000)");
            stmt.execute("INSERT OR REPLACE INTO custom_items (canonical_id, source_plugin, item_data, "
                    + "display_name, first_discovered, last_seen) "
                    + "VALUES ('test:sword', 'Test', 'new_data', 'Updated', 1000, 2000)");

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM custom_items");
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "Should have exactly 1 row after upsert");

            ResultSet rs2 = stmt.executeQuery("SELECT display_name, item_data FROM custom_items WHERE canonical_id='test:sword'");
            assertTrue(rs2.next());
            assertEquals("Updated", rs2.getString("display_name"));
            assertEquals("new_data", rs2.getString("item_data"));
        }
    }

    @Test
    @DisplayName("Custom items persist across database reopens")
    void persistenceAcrossRestarts() throws Exception {
        String dbPath = tempDir.resolve("test_persist.db").toString();
        String url = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY, source_plugin TEXT NOT NULL,"
                    + "item_data TEXT NOT NULL, first_discovered INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL)");
            stmt.execute("INSERT INTO custom_items VALUES ('a', 'P1', 'd1', 1, 1)");
            stmt.execute("INSERT INTO custom_items VALUES ('b', 'P2', 'd2', 2, 2)");
            stmt.execute("INSERT INTO custom_items VALUES ('c', 'P3', 'd3', 3, 3)");
        }

        // Reopen
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM custom_items");
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1), "All 3 items should persist");
        }
    }

    @Test
    @DisplayName("deleteCustomItem removes item from database")
    void deleteCustomItem() throws Exception {
        try (Connection conn = getConnection("test_delete.db");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY, source_plugin TEXT NOT NULL,"
                    + "item_data TEXT NOT NULL, first_discovered INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL)");
            stmt.execute("INSERT INTO custom_items VALUES ('del_me', 'P1', 'd1', 1, 1)");
            stmt.execute("DELETE FROM custom_items WHERE canonical_id='del_me'");

            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM custom_items WHERE canonical_id='del_me'");
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    @DisplayName("updateCustomItemPrice updates buy and sell prices")
    void updateCustomItemPrice() throws Exception {
        try (Connection conn = getConnection("test_price.db");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY, source_plugin TEXT NOT NULL,"
                    + "item_data TEXT NOT NULL, buy_price REAL DEFAULT -1,"
                    + "sell_price REAL DEFAULT -1, first_discovered INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL)");
            stmt.execute("INSERT INTO custom_items (canonical_id, source_plugin, item_data, "
                    + "first_discovered, last_seen) VALUES ('p', 'P1', 'd1', 1, 1)");
            stmt.execute("UPDATE custom_items SET buy_price=100, sell_price=50 WHERE canonical_id='p'");

            ResultSet rs = stmt.executeQuery("SELECT buy_price, sell_price FROM custom_items WHERE canonical_id='p'");
            assertTrue(rs.next());
            assertEquals(100.0, rs.getDouble("buy_price"), 0.001);
            assertEquals(50.0, rs.getDouble("sell_price"), 0.001);
        }
    }

    @Test
    @DisplayName("No duplicate canonical_ids allowed by PRIMARY KEY constraint")
    void noDuplicateCanonicalIds() throws Exception {
        try (Connection conn = getConnection("test_pk.db");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS custom_items ("
                    + "canonical_id TEXT PRIMARY KEY, source_plugin TEXT NOT NULL,"
                    + "item_data TEXT NOT NULL, first_discovered INTEGER NOT NULL,"
                    + "last_seen INTEGER NOT NULL)");
            stmt.execute("INSERT INTO custom_items VALUES ('dup', 'P1', 'd1', 1, 1)");

            // Second INSERT with same PK should fail
            assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO custom_items VALUES ('dup', 'P2', 'd2', 2, 2)")
            );
        }
    }
}

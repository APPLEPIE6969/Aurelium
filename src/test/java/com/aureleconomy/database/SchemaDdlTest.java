package com.aureleconomy.database;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.file.YamlConfiguration;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.schema.*;
import com.aureleconomy.database.types.MySQLTypes;
import com.aureleconomy.database.types.SQLTypes;
import com.aureleconomy.database.types.SQLiteTypes;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * Exercises the real Schema classes and DatabaseManager migrations against temporary SQLite
 * databases. Unlike DatabaseManagerTest — which writes its own DDL and never touches the
 * production classes — a change to a Schema will fail here.
 */
class SchemaDdlTest {

    @TempDir
    Path tempDir;

    private static List<Schema> schemas(SQLTypes t) {
        return List.of(
                new DatabaseInfoSchema(t),
                new PlayerSchema(t),
                new PlayerBalanceSchema(t),
                new AuctionSchema(t),
                new AuctionOfferSchema(t),
                new OfflineEarningSchema(t),
                new BuyOrderSchema(t),
                new PriceHistorySchema(t),
                new CustomItemSchema(t));
    }

    private Connection openSqlite(String dbName) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve(dbName));
    }

    private static Map<String, String> columnsOf(Connection conn, String table) throws SQLException {
        Map<String, String> cols = new LinkedHashMap<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.put(rs.getString("name"), rs.getString("type"));
            }
        }
        return cols;
    }

    private static List<String> tablesOf(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
                ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) {
                tables.add(rs.getString("name"));
            }
        }
        return tables;
    }

    // -------------------- Schema DDL --------------------

    @Test
    @DisplayName("Every schema produces DDL SQLite accepts")
    void allSchemasCreateValidSqliteDdl() throws Exception {
        try (Connection conn = openSqlite("ddl.db"); Statement stmt = conn.createStatement()) {
            for (Schema schema : schemas(new SQLiteTypes())) {
                assertDoesNotThrow(() -> stmt.execute(schema.create()),
                        "Invalid DDL for table " + schema.getTable());
            }

            List<String> tables = tablesOf(conn);
            for (Table table : Table.values()) {
                assertTrue(tables.contains(table.toString()), "Table not created: " + table);
            }
        }
    }

    @Test
    @DisplayName("Schemas declare the columns the plugin queries")
    void schemasDeclareExpectedColumns() throws Exception {
        try (Connection conn = openSqlite("columns.db"); Statement stmt = conn.createStatement()) {
            for (Schema schema : schemas(new SQLiteTypes())) {
                stmt.execute(schema.create());
            }

            assertEquals(List.of("uuid", "name", "gui_style"),
                    List.copyOf(columnsOf(conn, "players").keySet()));
            assertEquals(List.of("uuid", "currency", "balance"),
                    List.copyOf(columnsOf(conn, "player_balances").keySet()));
            assertEquals(List.of("id", "seller_uuid", "item_data", "price", "currency", "is_bin",
                    "expiration", "highest_bidder_uuid", "ended", "collected", "listing_fee",
                    "start_time", "purchase_mode"),
                    List.copyOf(columnsOf(conn, "auctions").keySet()));
            assertEquals(List.of("id", "auction_id", "bidder_uuid", "amount", "currency", "status",
                    "timestamp"),
                    List.copyOf(columnsOf(conn, "auction_offers").keySet()));
            assertEquals(List.of("id", "uuid", "amount", "currency", "item_display", "timestamp"),
                    List.copyOf(columnsOf(conn, "offline_earnings").keySet()));
            assertEquals(List.of("id", "buyer_uuid", "material", "amount_requested", "amount_filled",
                    "price_per_piece", "currency", "status"),
                    List.copyOf(columnsOf(conn, "buy_orders").keySet()));
            assertEquals(List.of("id", "item_key", "buy_price", "sell_price", "timestamp"),
                    List.copyOf(columnsOf(conn, "price_history").keySet()));
            assertEquals(List.of("canonical_id", "source_plugin", "display_name", "item_data",
                    "pdc_key", "model_data_key", "lore_hash", "plugin_native_id", "category",
                    "buy_price", "sell_price", "enabled", "discovery_methods", "first_discovered",
                    "last_seen"),
                    List.copyOf(columnsOf(conn, "custom_items").keySet()));
        }
    }

    @Test
    @DisplayName("custom_items keeps the SQLite-native types earlier versions wrote")
    void customItemsTypesUnchanged() throws Exception {
        try (Connection conn = openSqlite("custom_items_types.db"); Statement stmt = conn.createStatement()) {
            stmt.execute(new CustomItemSchema(new SQLiteTypes()).create());

            Map<String, String> cols = columnsOf(conn, "custom_items");
            assertEquals("TEXT", cols.get("canonical_id"));
            assertEquals("TEXT", cols.get("source_plugin"));
            assertEquals("TEXT", cols.get("item_data"));
            assertEquals("REAL", cols.get("buy_price"));
            assertEquals("REAL", cols.get("sell_price"));
            assertEquals("INTEGER", cols.get("enabled"));
            assertEquals("INTEGER", cols.get("first_discovered"));
            assertEquals("INTEGER", cols.get("last_seen"));
        }
    }

    @Test
    @DisplayName("Re-running create() on an existing database is a no-op")
    void createIsIdempotent() throws Exception {
        try (Connection conn = openSqlite("idempotent.db"); Statement stmt = conn.createStatement()) {
            for (Schema schema : schemas(new SQLiteTypes())) {
                stmt.execute(schema.create());
            }
            stmt.execute("INSERT INTO players (uuid, name) VALUES ('u1', 'Void')");

            for (Schema schema : schemas(new SQLiteTypes())) {
                stmt.execute(schema.create());
            }

            try (ResultSet rs = stmt.executeQuery("SELECT name FROM players WHERE uuid = 'u1'")) {
                assertTrue(rs.next());
                assertEquals("Void", rs.getString("name"), "Existing data must survive");
            }
        }
    }

    @Test
    @DisplayName("MySQL dialect differs only where it has to")
    void mySqlDialectDiffers() {
        String auctions = new AuctionSchema(new MySQLTypes()).create();
        assertTrue(auctions.contains("id INT AUTO_INCREMENT PRIMARY KEY"), auctions);
        assertTrue(auctions.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"), auctions);
        // BOOLEAN is spelled the same in both dialects.
        assertTrue(auctions.contains("is_bin BOOLEAN"), auctions);

        String customItems = new CustomItemSchema(new MySQLTypes()).create();
        assertTrue(customItems.contains("canonical_id VARCHAR(255) NOT NULL PRIMARY KEY"), customItems);
        assertTrue(customItems.contains("buy_price DOUBLE DEFAULT -1"), customItems);
        assertTrue(customItems.contains("enabled TINYINT(1) DEFAULT 1"), customItems);
        assertTrue(customItems.contains("first_discovered BIGINT NOT NULL"), customItems);
    }

    @Test
    @DisplayName("Timestamp columns are BIGINT on MySQL — LONG there means MEDIUMTEXT")
    void mySqlTimestampsAreBigInt() {
        assertEquals("BIGINT", new MySQLTypes().time());
        assertEquals("LONG", new SQLiteTypes().time());

        assertTrue(new AuctionSchema(new MySQLTypes()).create().contains("expiration BIGINT"));
        assertTrue(new AuctionSchema(new MySQLTypes()).create().contains("start_time BIGINT"));
        assertTrue(new AuctionOfferSchema(new MySQLTypes()).create().contains("timestamp BIGINT"));
        assertTrue(new OfflineEarningSchema(new MySQLTypes()).create().contains("timestamp BIGINT"));
        assertTrue(new PriceHistorySchema(new MySQLTypes()).create().contains("timestamp BIGINT"));

        // SQLite keeps the spelling earlier versions wrote — LONG has numeric affinity there.
        assertTrue(new AuctionSchema(new SQLiteTypes()).create().contains("expiration LONG"));
    }

    @Test
    @DisplayName("Identity columns are NOT NULL — SQLite would otherwise allow NULL keys")
    void identityColumnsRejectNull() throws Exception {
        try (Connection conn = openSqlite("not_null.db"); Statement stmt = conn.createStatement()) {
            for (Schema schema : schemas(new SQLiteTypes())) {
                stmt.execute(schema.create());
            }

            assertThrows(SQLException.class,
                    () -> stmt.execute("INSERT INTO players (uuid, name) VALUES (NULL, 'Void')"));
            assertThrows(SQLException.class,
                    () -> stmt.execute("INSERT INTO player_balances (uuid, currency, balance) "
                            + "VALUES (NULL, 'Aurels', 1.0)"));
            assertThrows(SQLException.class,
                    () -> stmt.execute("INSERT INTO player_balances (uuid, currency, balance) "
                            + "VALUES ('u1', NULL, 1.0)"));
            assertThrows(SQLException.class,
                    () -> stmt.execute("INSERT INTO custom_items (canonical_id, source_plugin, "
                            + "item_data, first_discovered, last_seen) VALUES (NULL, 'p', '{}', 1, 1)"));
        }
    }

    // -------------------- DatabaseManager end-to-end --------------------

    private AurelEconomy mockPlugin(File dataFolder) {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", "Aurels");
        config.set("economy.currencies.Aurels.symbol", "A");

        AurelEconomy plugin = Mockito.mock(AurelEconomy.class);
        Mockito.when(plugin.getConfig()).thenReturn(config);
        Mockito.when(plugin.getDataFolder()).thenReturn(dataFolder);
        Mockito.when(plugin.getComponentLogger()).thenReturn(Mockito.mock(ComponentLogger.class));
        return plugin;
    }

    @Test
    @DisplayName("Fresh install creates every table and records the latest schema version")
    void freshInstall() throws Exception {
        File dataFolder = tempDir.resolve("fresh").toFile();
        assertTrue(dataFolder.mkdirs());

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        assertFalse(manager.isMySQL());

        try (Connection conn = manager.getConnection()) {
            List<String> tables = tablesOf(conn);
            for (Table table : Table.values()) {
                assertTrue(tables.contains(table.toString()), "Missing table: " + table);
            }

            try (Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery("SELECT version FROM database_info")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt("version"));
            }
        }
        manager.close();
    }

    @Test
    @DisplayName("A pre-multi-currency database is migrated to v3 without data loss")
    void legacyDatabaseIsMigrated() throws Exception {
        File dataFolder = tempDir.resolve("legacy").toFile();
        assertTrue(dataFolder.mkdirs());
        String url = "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath();

        // Build a v0 database: single-currency balances, no database_info, no custom_items,
        // auctions without the columns added by migrations v1 and v3.
        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16), balance DOUBLE)");
            stmt.execute("INSERT INTO players VALUES ('11111111-1111-1111-1111-111111111111', 'Void', 250.5)");
            stmt.execute("CREATE TABLE auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid VARCHAR(36), "
                    + "item_data TEXT, price DOUBLE, is_bin BOOLEAN, expiration LONG, "
                    + "highest_bidder_uuid VARCHAR(36), ended BOOLEAN DEFAULT 0, collected BOOLEAN DEFAULT 0)");
            stmt.execute("INSERT INTO auctions (seller_uuid, price) VALUES ('11111111-1111-1111-1111-111111111111', 10.0)");
        }

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize(), "initialize() must succeed on a legacy database");

        try (Connection conn = manager.getConnection(); Statement stmt = conn.createStatement()) {
            // Legacy balance moved into player_balances under the configured default currency.
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT currency, balance FROM player_balances WHERE uuid = '11111111-1111-1111-1111-111111111111'")) {
                assertTrue(rs.next(), "Legacy balance was not migrated");
                assertEquals("Aurels", rs.getString("currency"));
                assertEquals(250.5, rs.getDouble("balance"), 0.0001);
            }

            // Existing auction row survived.
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS c FROM auctions")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("c"));
            }

            // Migration v1 and v3 columns were added to the pre-existing auctions table.
            var auctionCols = columnsOf(conn, "auctions").keySet();
            assertTrue(auctionCols.contains("currency"), "v1 did not add auctions.currency");
            assertTrue(auctionCols.contains("listing_fee"), "v1 did not add auctions.listing_fee");
            assertTrue(auctionCols.contains("start_time"), "v1 did not add auctions.start_time");
            assertTrue(auctionCols.contains("purchase_mode"), "v3 did not add auctions.purchase_mode");

            // Migration v1 added gui_style to the pre-existing players table.
            assertTrue(columnsOf(conn, "players").containsKey("gui_style"), "v1 did not add players.gui_style");

            // Migration v2 created custom_items.
            assertTrue(tablesOf(conn).contains("custom_items"), "v2 did not create custom_items");

            try (ResultSet rs = stmt.executeQuery("SELECT version FROM database_info")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt("version"));
            }
        }
        manager.close();
    }

    @Test
    @DisplayName("Re-initializing an up-to-date database changes nothing")
    void secondStartupIsClean() throws Exception {
        File dataFolder = tempDir.resolve("restart").toFile();
        assertTrue(dataFolder.mkdirs());

        DatabaseManager first = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(first.initialize());
        try (Statement stmt = first.getConnection().createStatement()) {
            stmt.execute("INSERT INTO player_balances VALUES ('u1', 'Aurels', 42.0)");
        }
        first.close();

        DatabaseManager second = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(second.initialize());
        try (Statement stmt = second.getConnection().createStatement();
                ResultSet rs = stmt.executeQuery("SELECT balance FROM player_balances WHERE uuid = 'u1'")) {
            assertTrue(rs.next(), "Balance did not survive the restart");
            assertEquals(42.0, rs.getDouble("balance"), 0.0001);
        }
        second.close();
    }

    @Test
    @DisplayName("database.type accepts sqlite, mysql and mariadb, and falls back otherwise")
    void databaseTypeResolution() {
        assertEquals(DatabaseType.SQLITE, DatabaseType.fromConfig("sqlite"));
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromConfig("mysql"));
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromConfig("MariaDB"));
        assertEquals(DatabaseType.MYSQL, DatabaseType.fromConfig(" mysql "));
        assertEquals(DatabaseType.SQLITE, DatabaseType.fromConfig("postgres"));
        assertEquals(DatabaseType.SQLITE, DatabaseType.fromConfig(null));
    }

    @Test
    @DisplayName("database.file may not point outside the plugin folder")
    void sqliteFileStaysInsideDataFolder() throws Exception {
        File dataFolder = tempDir.resolve("escape").toFile();
        assertTrue(dataFolder.mkdirs());

        for (String hostile : List.of("../outside.db", "sub/../../outside.db",
                new File(tempDir.toFile(), "absolute.db").getAbsolutePath())) {
            AurelEconomy plugin = mockPlugin(dataFolder);
            plugin.getConfig().set("database.file", hostile);

            DatabaseManager manager = new DatabaseManager(plugin);
            assertFalse(manager.initialize(), "initialize() must reject database.file " + hostile);
            assertFalse(new File(tempDir.toFile(), "outside.db").exists(),
                    "Database was created outside the plugin folder for " + hostile);
            assertFalse(new File(tempDir.toFile(), "absolute.db").exists(),
                    "Database was created outside the plugin folder for " + hostile);
        }

        // A relative path in a subdirectory of the data folder is still fine.
        AurelEconomy plugin = mockPlugin(dataFolder);
        plugin.getConfig().set("database.file", "data/database.db");
        DatabaseManager manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "A path inside the data folder must be accepted");
        assertTrue(new File(dataFolder, "data/database.db").exists());
        manager.close();
    }

    @Test
    @DisplayName("getConnection reopens a closed connection")
    void getConnectionReconnects() throws Exception {
        File dataFolder = tempDir.resolve("reconnect").toFile();
        assertTrue(dataFolder.mkdirs());

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize());

        manager.close();
        Connection reopened = manager.getConnection();
        assertNotNull(reopened, "getConnection() must reopen after close()");
        assertFalse(reopened.isClosed());

        manager.close();
    }
}

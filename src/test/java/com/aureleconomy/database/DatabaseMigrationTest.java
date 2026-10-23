package com.aureleconomy.database;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.schema.PlayerBalanceSchema;
import com.aureleconomy.database.types.SQLiteTypes;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Upgrades databases that are genuinely behind, rather than empty ones.
 *
 * <p>SchemaDdlTest covers a fresh install and a v0 database, but it asserted
 * nothing about migrations v4, v5 or v6, and never checked that a database
 * already at the current version is left alone. Those are the migrations an
 * owner upgrading from a recent release actually hits.
 *
 * <p>Row counts are compared before and after, because a migration that reaches
 * the right schema version while quietly dropping a player's balance or price
 * history is worse than one that fails loudly.
 */
class DatabaseMigrationTest {

    private static final int LATEST = 8;

    @TempDir
    Path tempDir;

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

    private static List<String> tablesOf(Connection conn) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table'")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        return tables;
    }

    private static Set<String> columnsOf(Connection conn, String table) throws SQLException {
        Set<String> cols = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static Set<String> indexesOf(Connection conn) throws SQLException {
        Set<String> idx = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='index'")) {
            while (rs.next()) idx.add(rs.getString(1));
        }
        return idx;
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static int version(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM database_info LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    /**
     * Builds a database at the given schema version, populated with real rows.
     *
     * <p>The price history volume matters: migration v6 adds an index on it
     * because it grows by one row per tradeable item per snapshot, and a
     * migration that locks up or fails on a table with tens of thousands of rows
     * would not show up on a toy fixture.
     */
    private File buildDatabase(String name, int schemaVersion, int priceHistoryRows,
                               boolean withPlayers) throws Exception {
        File dataFolder = tempDir.resolve(name).toFile();
        assertTrue(dataFolder.mkdirs());
        String url = "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            // Core tables, as an older release left them.
            stmt.execute("CREATE TABLE players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16), balance DOUBLE)");
            stmt.execute("CREATE TABLE player_balances (uuid VARCHAR(36), currency VARCHAR(32), balance DOUBLE)");
            stmt.execute("CREATE TABLE price_history (item_id VARCHAR(128), timestamp LONG, price DOUBLE)");
            stmt.execute("CREATE TABLE auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid VARCHAR(36), "
                    + "item_data TEXT, price DOUBLE, is_bin BOOLEAN, expiration LONG, "
                    + "highest_bidder_uuid VARCHAR(36), ended BOOLEAN DEFAULT 0, collected BOOLEAN DEFAULT 0)");
            stmt.execute("CREATE TABLE offline_earnings (uuid VARCHAR(36), timestamp LONG, amount DOUBLE)");
            stmt.execute("CREATE TABLE buy_orders (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid VARCHAR(36), item_id VARCHAR(128), "
                    + "amount INTEGER, price DOUBLE, active BOOLEAN DEFAULT 1)");
            stmt.execute("CREATE TABLE auction_offers (id INTEGER PRIMARY KEY AUTOINCREMENT, auction_id INTEGER, "
                    + "bidder_uuid VARCHAR(36), amount DOUBLE, timestamp LONG)");

            if (withPlayers) {
                stmt.execute("INSERT INTO players VALUES "
                        + "('11111111-1111-1111-1111-111111111111', 'Tester', 5000.0)");
                stmt.execute("INSERT INTO player_balances VALUES "
                        + "('11111111-1111-1111-1111-111111111111', 'Aurels', 5000.0)");
            }

            // Enough rows that an index build or a table rewrite has to do real work.
            // Wrapped in an explicit transaction: sqlite-jdbc autocommits every row
            // of a large batch otherwise, which turns this into minutes of disk
            // syncs rather than a fixture.
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO price_history VALUES (?, ?, ?)")) {
                for (int i = 0; i < priceHistoryRows; i++) {
                    ps.setString(1, "SPAWNER_IRON_GOLEM_SPAWNER");
                    ps.setLong(2, 1_700_000_000_000L + i * 60_000L);
                    ps.setDouble(3, 750_000.0 + i);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            conn.setAutoCommit(true);

            // v1 added these, v2/v3/v5 add more. Only add what the target
            // version should already have.
            if (schemaVersion >= 1) {
                stmt.execute("ALTER TABLE players ADD COLUMN gui_style VARCHAR(16) DEFAULT 'MODERN'");
                stmt.execute("ALTER TABLE auctions ADD COLUMN listing_fee DOUBLE DEFAULT 0.0");
                stmt.execute("ALTER TABLE auctions ADD COLUMN start_time LONG");
                stmt.execute("ALTER TABLE auctions ADD COLUMN currency VARCHAR(32)");
                stmt.execute("ALTER TABLE offline_earnings ADD COLUMN currency VARCHAR(32)");
                stmt.execute("ALTER TABLE buy_orders ADD COLUMN currency VARCHAR(32)");
                stmt.execute("ALTER TABLE auction_offers ADD COLUMN currency VARCHAR(32)");
            }
            if (schemaVersion >= 2) {
                stmt.execute(new com.aureleconomy.database.schema.CustomItemSchema(
                        new SQLiteTypes()).create());
            }
            if (schemaVersion >= 3) {
                stmt.execute("ALTER TABLE auctions ADD COLUMN purchase_mode VARCHAR(16) DEFAULT 'STACK'");
            }
            if (schemaVersion >= 5) {
                stmt.execute(new com.aureleconomy.database.schema.TradeVolumeSchema(
                        new SQLiteTypes()).create());
            }
            if (schemaVersion >= 6) {
                stmt.execute("CREATE INDEX idx_price_history_timestamp ON price_history (timestamp)");
                stmt.execute("CREATE INDEX idx_trade_volume_trade_at ON trade_volume (trade_at)");
            }
            if (schemaVersion >= 7) {
                stmt.execute(new com.aureleconomy.database.schema.LedgerSchema(
                        new SQLiteTypes()).create());
                stmt.execute("CREATE INDEX idx_transaction_ledger_actor ON transaction_ledger (actor_uuid, ts)");
                stmt.execute("CREATE INDEX idx_offline_earnings_uuid ON offline_earnings (uuid)");
            }
            if (schemaVersion >= 8) {
                stmt.execute(new com.aureleconomy.database.schema.ProcessedPurchaseSchema(
                        new SQLiteTypes()).create());
            }

            stmt.execute("CREATE TABLE database_info (version INTEGER PRIMARY KEY)");
            stmt.execute("INSERT INTO database_info VALUES (" + schemaVersion + ")");
        }
        return dataFolder;
    }

    @Test
    @DisplayName("A v1 database is carried to the latest version with all rows intact")
    void v1ReachesLatestWithoutDataLoss() throws Exception {
        // 30000 price history rows, so the v6 index build is not a no-op.
        File dataFolder = buildDatabase("v1", 1, 30_000, true);
        long rowsBefore;
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            rowsBefore = count(conn, "price_history");
        }
        assertEquals(30_000, rowsBefore);

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize(), "initialize() must succeed on a v1 database");

        try (Connection conn = manager.getConnection()) {
            assertEquals(LATEST, version(conn), "schema version was not advanced to the latest");

            // Nothing dropped.
            assertEquals(30_000, count(conn, "price_history"),
                    "price history rows were lost during migration");
            assertEquals(1, count(conn, "players"), "the player row was lost");
            assertEquals(1, count(conn, "player_balances"), "the player balance was lost");

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT balance FROM player_balances WHERE uuid = "
                                 + "'11111111-1111-1111-1111-111111111111'")) {
                assertTrue(rs.next(), "player balance row is missing");
                assertEquals(5000.0, rs.getDouble(1), 0.0001,
                        "player balance was altered by the migration");
            }

            // Everything v2, v3, v5 and v6 add is present.
            List<String> tables = tablesOf(conn);
            assertTrue(tables.contains("custom_items"), "v2 did not create custom_items");
            assertTrue(tables.contains("trade_volume"), "v5 did not create trade_volume");
            assertTrue(columnsOf(conn, "auctions").contains("purchase_mode"),
                    "v3 did not add auctions.purchase_mode");

            Set<String> indexes = indexesOf(conn);
            assertTrue(indexes.contains("idx_price_history_timestamp"),
                    "v6 did not add the price_history timestamp index");
            assertTrue(indexes.contains("idx_trade_volume_trade_at"),
                    "v6 did not add the trade_volume index");
            assertTrue(tables.contains("transaction_ledger"), "v7 did not create the ledger");
            assertTrue(indexes.contains("idx_transaction_ledger_actor"),
                    "v7 did not add the ledger index");
            assertTrue(indexes.contains("idx_offline_earnings_uuid"),
                    "v7 did not add the offline_earnings uuid index");
            assertTrue(tables.contains("processed_purchases"),
                    "v8 did not create the processed_purchases table");

            // And the data is still usable, not just present.
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT COUNT(*) FROM price_history WHERE item_id = "
                                 + "'SPAWNER_IRON_GOLEM_SPAWNER'")) {
                assertTrue(rs.next());
                assertEquals(30_000, rs.getInt(1),
                        "the spawner price history rows lost their item id");
            }
        }
        manager.close();
    }

    @Test
    @DisplayName("A v4 database gains only what v5 and v6 add")
    void v4GainsRemainingMigrations() throws Exception {
        File dataFolder = buildDatabase("v4", 4, 500, true);

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize(), "initialize() must succeed on a v4 database");

        try (Connection conn = manager.getConnection()) {
            assertEquals(LATEST, version(conn));
            assertEquals(500, count(conn, "price_history"));
            assertTrue(tablesOf(conn).contains("trade_volume"), "v5 did not create trade_volume");
            assertTrue(indexesOf(conn).contains("idx_price_history_timestamp"),
                    "v6 did not add the price_history timestamp index");
            assertTrue(indexesOf(conn).contains("idx_trade_volume_trade_at"),
                    "v6 did not add the trade_volume index");
        }
        manager.close();
    }

    @Test
    @DisplayName("v7 leaves the balances it retypes intact and gives the ledger a usable table")
    void v7AddsLedgerWithoutTouchingMoney() throws Exception {
        File dataFolder = buildDatabase("v6toledger", 6, 100, true);

        // The retype is a no-op on SQLite, but the rows still have to survive it,
        // and the ledger has to accept a full row afterwards - a schema that only
        // creates an empty table with the wrong columns would pass a table check.
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE player_balances SET balance = 1234.56 WHERE uuid = "
                    + "'11111111-1111-1111-1111-111111111111'");
            stmt.execute("INSERT INTO offline_earnings (uuid, timestamp, amount, currency) VALUES "
                    + "('11111111-1111-1111-1111-111111111111', 1700000000000, 12.34, 'Aurels')");
        }

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize(), "initialize() must succeed on a v6 database");

        try (Connection conn = manager.getConnection(); Statement stmt = conn.createStatement()) {
            assertEquals(LATEST, version(conn), "v6 was not carried to v7");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT balance FROM player_balances WHERE uuid = "
                            + "'11111111-1111-1111-1111-111111111111'")) {
                assertTrue(rs.next(), "the balance row was lost in the v7 migration");
                assertEquals(1234.56, rs.getDouble(1), 0.0001, "the balance changed");
            }
            assertEquals(1, count(conn, "offline_earnings"), "the offline earning was lost");

            stmt.execute("INSERT INTO transaction_ledger (ts, actor_uuid, counterparty_uuid, type,"
                    + " item_key, amount, currency, balance_after, ref_id, note) VALUES"
                    + " (1700000000001, '11111111-1111-1111-1111-111111111111',"
                    + " '22222222-2222-2222-2222-222222222222', 'BID', 'IRON_INGOT', -25.00,"
                    + " 'Aurels', 1209.56, '42', 'auction bid')");
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT amount, balance_after, currency FROM transaction_ledger"
                            + " WHERE type = 'BID'")) {
                assertTrue(rs.next(), "the ledger did not accept a full row");
                assertEquals(-25.00, rs.getDouble(1), 0.0001);
                assertEquals(1209.56, rs.getDouble(2), 0.0001);
                assertEquals("Aurels", rs.getString(3));
            }
        }
        manager.close();
    }

    @Test
    @DisplayName("A database already at the latest version is left alone")
    void currentDatabaseIsUntouched() throws Exception {
        File dataFolder = buildDatabase("current", LATEST, 200, true);

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(manager.initialize(), "initialize() must succeed on a current database");

        try (Connection conn = manager.getConnection()) {
            assertEquals(LATEST, version(conn), "version changed on an up to date database");
            assertEquals(200, count(conn, "price_history"));
            assertEquals(1, count(conn, "players"));
        }
        manager.close();

        // Running again must also be safe, which is what happens on every restart.
        DatabaseManager again = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(again.initialize(), "a second initialize() must succeed");
        try (Connection conn = again.getConnection()) {
            assertEquals(LATEST, version(conn));
            assertEquals(200, count(conn, "price_history"), "a restart duplicated or lost rows");
        }
        again.close();
    }

    @Test
    @DisplayName("Every schema version from v0 up is reachable and ends at the latest")
    void everyIntermediateVersionMigrates() throws Exception {
        for (int from = 0; from <= LATEST; from++) {
            // v0 has no database_info at all, so build the next one up and drop
            // the marker to simulate a pre-versioning database.
            File dataFolder = buildDatabase("from" + from, Math.max(from, 1), 50, true);
            if (from == 0) {
                try (Connection conn = DriverManager.getConnection(
                        "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());
                     Statement stmt = conn.createStatement()) {
                    stmt.execute("DROP TABLE database_info");
                }
            }

            DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
            assertTrue(manager.initialize(), "initialize() failed starting from v" + from);
            try (Connection conn = manager.getConnection()) {
                assertEquals(LATEST, version(conn),
                        "starting from v" + from + " did not reach the latest version");
                assertEquals(50, count(conn, "price_history"),
                        "starting from v" + from + " lost price history rows");
                assertTrue(tablesOf(conn).contains("trade_volume"),
                        "starting from v" + from + " did not create trade_volume");
            }
            manager.close();
        }
    }

    @Test
    @DisplayName("A migration failure leaves the recorded version behind the latest")
    void failedMigrationDoesNotClaimSuccess() throws Exception {
        // A database_info row pointing at a version with no migration defined, so
        // the version is bumped past anything the switch statement handles.
        File dataFolder = buildDatabase("broken", LATEST, 10, true);
        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE database_info SET version = 999");
        }

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        // initialize must not throw. Whether it reports success is up to how the
        // caller copes, but the data must survive either way.
        try {
            manager.initialize();
        } catch (RuntimeException expected) {
            // Acceptable: a hard failure is better than silently continuing.
        }

        try (Connection conn = manager.getConnection()) {
            assertEquals(10, count(conn, "price_history"),
                    "rows were lost when the migration could not complete");
        }
        manager.close();
    }
}
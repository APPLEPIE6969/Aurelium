package com.aureleconomy.database;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.schema.CustomItemSchema;
import com.aureleconomy.database.types.SQLiteTypes;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a migration that stops half way actually leaves behind (M11).
 *
 * <p>DatabaseMigrationTest's failure case set database_info to a version above the
 * latest, which makes runMigrations return before it applies anything: it asserted
 * that the data survived a migration that never ran. This test makes a real step
 * fail in the middle of a run, with the version row still pointing at the version it
 * started from, and then asks what the schema looks like afterwards.
 */
class DatabaseMigrationRollbackTest {

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

    /**
     * A v5 database (so v6 and v7 still have work to do) carrying an index whose name
     * v7 wants to create, on a table v7 does not touch. Creating the index is what
     * makes the migration fail, on the step that runs it, not before the run starts.
     */
    private File buildObstructedDatabase() throws Exception {
        File dataFolder = tempDir.resolve("rollback").toFile();
        assertTrue(dataFolder.mkdirs());
        String url = "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath();

        try (Connection conn = DriverManager.getConnection(url); Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16), balance DOUBLE,"
                    + " gui_style VARCHAR(16) DEFAULT 'MODERN')");
            stmt.execute("CREATE TABLE player_balances (uuid VARCHAR(36), currency VARCHAR(32), balance DOUBLE)");
            stmt.execute("CREATE TABLE price_history (item_id VARCHAR(128), timestamp LONG, price DOUBLE)");
            stmt.execute("CREATE TABLE auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller_uuid VARCHAR(36),"
                    + " item_data TEXT, price DOUBLE, is_bin BOOLEAN, expiration LONG,"
                    + " highest_bidder_uuid VARCHAR(36), ended BOOLEAN DEFAULT 0, collected BOOLEAN DEFAULT 0,"
                    + " listing_fee DOUBLE DEFAULT 0.0, start_time LONG, currency VARCHAR(32),"
                    + " purchase_mode VARCHAR(16) DEFAULT 'STACK')");
            stmt.execute("CREATE TABLE offline_earnings (uuid VARCHAR(36), timestamp LONG, amount DOUBLE,"
                    + " currency VARCHAR(32))");
            stmt.execute("CREATE TABLE buy_orders (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid VARCHAR(36),"
                    + " item_id VARCHAR(128), amount INTEGER, price DOUBLE, active BOOLEAN DEFAULT 1,"
                    + " currency VARCHAR(32))");
            stmt.execute("CREATE TABLE auction_offers (id INTEGER PRIMARY KEY AUTOINCREMENT, auction_id INTEGER,"
                    + " bidder_uuid VARCHAR(36), amount DOUBLE, timestamp LONG, currency VARCHAR(32))");
            stmt.execute(new CustomItemSchema(new SQLiteTypes()).create());

            stmt.execute("INSERT INTO players VALUES"
                    + " ('11111111-1111-1111-1111-111111111111', 'Tester', 5000.0, 'MODERN')");
            stmt.execute("INSERT INTO player_balances VALUES"
                    + " ('11111111-1111-1111-1111-111111111111', 'Aurels', 5000.0)");
            stmt.execute("INSERT INTO price_history VALUES ('SPAWNER_IRON_GOLEM_SPAWNER', 1700000000000, 750000.0)");
            stmt.execute("INSERT INTO price_history VALUES ('SPAWNER_IRON_GOLEM_SPAWNER', 1700000060000, 750001.0)");

            // The obstruction: v7 creates idx_transaction_ledger_actor on
            // transaction_ledger, and the name is already taken here.
            stmt.execute("CREATE INDEX idx_transaction_ledger_actor ON players (uuid)");

            stmt.execute("CREATE TABLE database_info (version INTEGER PRIMARY KEY)");
            stmt.execute("INSERT INTO database_info VALUES (5)");
        }
        return dataFolder;
    }

    private static int version(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version FROM database_info LIMIT 1")) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    private static Set<String> indexesOf(Connection conn) throws SQLException {
        Set<String> indexes = new LinkedHashSet<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
            while (rs.next()) indexes.add(rs.getString(1));
        }
        return indexes;
    }

    private static long count(Connection conn, String table) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("a migration that fails half way leaves the previous version and no half-applied step")
    void failedStepRollsBackAndKeepsTheVersionBehind() throws Exception {
        File dataFolder = buildObstructedDatabase();

        DatabaseManager manager = new DatabaseManager(mockPlugin(dataFolder));
        manager.initialize();

        try (Connection conn = manager.getConnection()) {
            assertEquals(5, version(conn),
                    "a failed migration must not record the version it did not reach");

            // v6 runs before v7 and creates these two indexes in the same transaction.
            // If the rollback works, they are gone again.
            Set<String> indexes = indexesOf(conn);
            assertFalse(indexes.contains("idx_price_history_timestamp"),
                    "v6's index survived a rollback, so the migration was not atomic");
            assertFalse(indexes.contains("idx_trade_volume_trade_at"),
                    "v6's second index survived a rollback");

            assertEquals(2, count(conn, "price_history"), "the migration lost rows while failing");
            assertEquals(1, count(conn, "players"), "the player row was lost while failing");

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT balance FROM player_balances WHERE uuid ="
                                 + " '11111111-1111-1111-1111-111111111111'")) {
                assertTrue(rs.next(), "the balance row is gone");
                assertEquals(5000.0, rs.getDouble(1), 0.0001, "the balance changed while failing");
            }
        }
        manager.close();
    }

    @Test
    @DisplayName("once the obstruction is gone the same database finishes the migration")
    void theNextStartResumesTheMigration() throws Exception {
        File dataFolder = buildObstructedDatabase();

        DatabaseManager first = new DatabaseManager(mockPlugin(dataFolder));
        first.initialize();
        first.close();

        try (Connection conn = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());
             Statement stmt = conn.createStatement()) {
            assertEquals(5, version(conn));
            stmt.execute("DROP INDEX idx_transaction_ledger_actor");
        }

        DatabaseManager second = new DatabaseManager(mockPlugin(dataFolder));
        assertTrue(second.initialize(), "the retry on the same database must succeed");

        try (Connection conn = second.getConnection()) {
            assertEquals(LATEST, version(conn), "the retry did not reach the latest version");
            Set<String> indexes = indexesOf(conn);
            assertTrue(indexes.contains("idx_price_history_timestamp"), "v6's index is still missing");
            assertTrue(indexes.contains("idx_trade_volume_trade_at"), "v6's second index is still missing");
            assertTrue(indexes.contains("idx_transaction_ledger_actor"), "v7's index is still missing");
            assertEquals(2, count(conn, "price_history"), "the retry lost rows");
        }
        second.close();
    }

    @Test
    @DisplayName("v4 turns text timestamps into millis before it retypes the column")
    void v4NormalisesDirtyTimestampsFirst() {
        List<String> statements = DatabaseManager.timeColumnConversionStatements("auctions", "expiration");

        assertEquals(3, statements.size(), "two normalisation passes and the retype");
        String retype = statements.get(2);
        assertEquals("ALTER TABLE auctions MODIFY COLUMN expiration BIGINT", retype,
                "the retype has to come after the values are normalised, or it fails on a datetime");
        assertFalse(statements.get(0).startsWith("ALTER"), "a retype before normalisation is the old code");

        String datetimes = statements.get(0);
        assertTrue(datetimes.contains("UNIX_TIMESTAMP(`expiration`)"),
                "the datetime shapes earlier releases wrote have to be parsed");
        assertTrue(datetimes.contains("* 1000"),
                "the rest of the schema stores epoch millis, not seconds");
        assertTrue(datetimes.contains("`expiration` NOT REGEXP '^[0-9]+$'"),
                "already numeric values must not be touched");
        assertTrue(datetimes.contains("UPDATE `auctions`"), "the statement names the table it converts");

        String leftovers = statements.get(1);
        assertTrue(leftovers.contains("SET `expiration` = 0"),
                "anything unparseable has to become the schema's unset value, or the ALTER still fails");
        assertTrue(leftovers.contains("NOT REGEXP '^[0-9]+$'"),
                "the catch-all must not zero values that are already epoch millis");
    }
}

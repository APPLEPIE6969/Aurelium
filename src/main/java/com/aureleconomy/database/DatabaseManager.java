package com.aureleconomy.database;

import com.aureleconomy.AurelEconomy;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final AurelEconomy plugin;
    private Connection connection;
    private String databaseType;

    public DatabaseManager(AurelEconomy plugin) {
        this.plugin = plugin;
        this.databaseType = plugin.getConfig().getString("database.type", "sqlite").toLowerCase();
    }

    private static final int LATEST_SCHEMA_VERSION = 1;

    public boolean initialize() {
        try {
            if ("mysql".equals(databaseType)) {
                initializeMySQL();
            } else {
                initializeSQLite();
            }
            createTables();
            runMigrations();
            return true;
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not initialize database (" + databaseType + ")!", e);
            return false;
        }
    }

    public void backupDatabase(String version) {
        if (!"sqlite".equals(databaseType))
            return;

        File dbFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "database.db"));
        if (!dbFile.exists())
            return;

        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists()) {
            backupFolder.mkdirs();
        }

        File backupFile = new File(backupFolder, "database_v" + version + "_" + System.currentTimeMillis() + ".db");
        try {
            java.nio.file.Files.copy(dbFile.toPath(), backupFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            plugin.getComponentLogger().info("Database backup created: " + backupFile.getName());
        } catch (java.io.IOException e) {
            plugin.getComponentLogger().error("Failed to create database backup!", e);
        }
    }

    private void initializeSQLite() throws SQLException {
        File dataFolder = new File(plugin.getDataFolder(),
                plugin.getConfig().getString("database.file", "database.db"));
        if (!dataFolder.getParentFile().exists()) {
            dataFolder.getParentFile().mkdirs();
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());

        // Enable WAL mode and optimize SQLite for concurrent access
        try (Statement stmt = connection.createStatement()) {
            // WAL mode allows concurrent reads during writes
            stmt.execute("PRAGMA journal_mode=WAL;");
            // Busy timeout: wait up to 30 seconds for locks to clear
            stmt.execute("PRAGMA busy_timeout=30000;");
            // Synchronous mode for balance between safety and performance
            stmt.execute("PRAGMA synchronous=NORMAL;");
            // Increase cache size for better performance
            stmt.execute("PRAGMA cache_size=-10000;");
        }
    }

    private void initializeMySQL() throws SQLException {
        FileConfiguration config = plugin.getConfig();
        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "aurelium");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "");
        boolean useSSL = config.getBoolean("database.mysql.use-ssl", true);

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?autoReconnect=true&useSSL=" + useSSL;
        connection = DriverManager.getConnection(url, username, password);
    }

    private void createTables() {
        String autoIncrement = "mysql".equals(databaseType) ? "INT AUTO_INCREMENT PRIMARY KEY"
                : "INTEGER PRIMARY KEY AUTOINCREMENT";

        try (Statement statement = connection.createStatement()) {
            // Internal versioning table
            statement.execute("CREATE TABLE IF NOT EXISTS database_info (" +
                    "version INTEGER PRIMARY KEY" +
                    ");");

            // Players table
            statement.execute("CREATE TABLE IF NOT EXISTS players (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "name VARCHAR(16), " +
                    "gui_style VARCHAR(16) DEFAULT 'MODERN'" +
                    ");");

            // Player Balances table
            statement.execute("CREATE TABLE IF NOT EXISTS player_balances (" +
                    "uuid VARCHAR(36), " +
                    "currency VARCHAR(32), " +
                    "balance DOUBLE NOT NULL DEFAULT 0.0, " +
                    "PRIMARY KEY (uuid, currency)" +
                    ");");

            // Migrate legacy single-currency balances to player_balances if needed
            migrateLegacyBalances();

            // Auctions table
            statement.execute("CREATE TABLE IF NOT EXISTS auctions (" +
                    "id " + autoIncrement + ", " +
                    "seller_uuid VARCHAR(36), " +
                    "item_data TEXT, " +
                    "price DOUBLE, " +
                    "currency VARCHAR(32), " +
                    "is_bin BOOLEAN, " +
                    "expiration LONG, " +
                    "highest_bidder_uuid VARCHAR(36), " +
                    "ended BOOLEAN DEFAULT 0, " +
                    "collected BOOLEAN DEFAULT 0, " +
                    "listing_fee DOUBLE DEFAULT 0.0, " +
                    "start_time LONG" +
                    ");");

            // Offline Earnings table
            statement.execute("CREATE TABLE IF NOT EXISTS offline_earnings (" +
                    "id " + autoIncrement + ", " +
                    "uuid VARCHAR(36), " +
                    "amount DOUBLE, " +
                    "currency VARCHAR(32), " +
                    "item_display VARCHAR(64), " +
                    "timestamp LONG" +
                    ");");

            // Buy Orders table
            statement.execute("CREATE TABLE IF NOT EXISTS buy_orders (" +
                    "id " + autoIncrement + ", " +
                    "buyer_uuid VARCHAR(36), " +
                    "material VARCHAR(64), " +
                    "amount_requested INTEGER, " +
                    "amount_filled INTEGER DEFAULT 0, " +
                    "price_per_piece DOUBLE, " +
                    "currency VARCHAR(32), " +
                    "status VARCHAR(16) DEFAULT 'ACTIVE'" +
                    ");");

            // Price History table
            statement.execute("CREATE TABLE IF NOT EXISTS price_history (" +
                    "id " + autoIncrement + ", " +
                    "item_key VARCHAR(128), " +
                    "buy_price DOUBLE, " +
                    "sell_price DOUBLE, " +
                    "timestamp LONG" +
                    ")");

            createOffersTable(autoIncrement);

        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not create tables for " + databaseType + "!", e);
        }
    }

    private void runMigrations() {
        int currentVersion = getDatabaseVersion();
        if (currentVersion >= LATEST_SCHEMA_VERSION)
            return;

        plugin.getComponentLogger().info("Database outdated (v" + currentVersion
                + "). Starting automatic migration to v" + LATEST_SCHEMA_VERSION + "...");

        try {
            // Disable auto-commit for atomicity if supported
            connection.setAutoCommit(false);

            for (int i = currentVersion + 1; i <= LATEST_SCHEMA_VERSION; i++) {
                plugin.getComponentLogger().info("Applying database migration v" + i + "...");
                applyMigration(i);
            }

            updateDatabaseVersion(LATEST_SCHEMA_VERSION);
            connection.commit();
            plugin.getComponentLogger().info("Database migration completed successfully.");
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                /* ignored */ }
            plugin.getComponentLogger().error("Database migration FAILED! Some features might be broken.", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                /* ignored */ }
        }
    }

    private int getDatabaseVersion() {
        try (Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT version FROM database_info LIMIT 1");
            if (rs.next())
                return rs.getInt("version");
        } catch (SQLException e) {
            // Likely fresh database or first update to versioning system
        }
        return 0;
    }

    private void updateDatabaseVersion(int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM database_info;");
            statement.execute("INSERT INTO database_info (version) VALUES (" + version + ");");
        }
    }

    private void applyMigration(int version) throws SQLException {
        switch (version) {
            case 1:
                // Migration to version 1: Ensure all legacy patch columns exist
                // This consolidates all previous addColumnIfNotExists calls
                addColumnIfNotExists("players", "gui_style", "VARCHAR(16) DEFAULT 'MODERN'");
                addColumnIfNotExists("auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
                addColumnIfNotExists("auctions", "start_time", "LONG");
                addColumnIfNotExists("auctions", "currency", "VARCHAR(32)");
                addColumnIfNotExists("offline_earnings", "currency", "VARCHAR(32)");
                addColumnIfNotExists("buy_orders", "currency", "VARCHAR(32)");
                addColumnIfNotExists("auction_offers", "currency", "VARCHAR(32)");
                break;
        }
    }

    private void createOffersTable(String autoIncrement) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auction_offers (" +
                    "id " + autoIncrement + ", " +
                    "auction_id INTEGER, " +
                    "bidder_uuid VARCHAR(36), " +
                    "amount DOUBLE, " +
                    "currency VARCHAR(32), " +
                    "status VARCHAR(16) DEFAULT 'PENDING', " +
                    "timestamp LONG, " +
                    "FOREIGN KEY(auction_id) REFERENCES auctions(id)" +
                    ");");
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not create offers table for " + databaseType + "!", e);
        }
    }

    private boolean migrationChecked = false;

    private void migrateLegacyBalances() {
        if (migrationChecked)
            return;
        migrationChecked = true;

        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT balance FROM players LIMIT 1");

            plugin.getComponentLogger()
                    .info("Legacy single-currency database detected. Migrating to multi-currency system...");
            String defaultCurrency = plugin.getConfig().getString("economy.default-currency", "Aurels");

            // Use direct insert for compatibility
            statement.execute("INSERT INTO player_balances (uuid, currency, balance) " +
                    "SELECT uuid, '" + defaultCurrency + "', balance FROM players " +
                    "WHERE uuid NOT IN (SELECT uuid FROM player_balances WHERE currency = '" + defaultCurrency + "');");

            try {
                statement.execute("ALTER TABLE players DROP COLUMN balance;");
            } catch (SQLException dropError) {
                // If drop fails (old SQLite), migrationChecked flag handles it
            }

            plugin.getComponentLogger().info("Multi-currency database migration completed successfully.");
        } catch (SQLException e) {
            // column missing = already migrated
        }
    }

    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                if ("mysql".equals(databaseType)) {
                    initializeMySQL();
                } else {
                    initializeSQLite();
                }
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Failed to re-establish " + databaseType + " database connection!", e);
        }
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not close database connection!", e);
        }
    }

    private void addColumnIfNotExists(String table, String column, String type) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.executeQuery("SELECT " + column + " FROM " + table + " LIMIT 1");
                return;
            } catch (SQLException e) {
                // missing
            }
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }
}

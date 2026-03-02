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

    public boolean initialize() {
        try {
            if ("mysql".equals(databaseType)) {
                return initializeMySQL();
            } else {
                return initializeSQLite();
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not initialize database (" + databaseType + ")!", e);
            return false;
        }
    }

    private boolean initializeSQLite() throws SQLException {
        File dataFolder = new File(plugin.getDataFolder(), plugin.getConfig().getString("database.file", "database.db"));
        if (!dataFolder.getParentFile().exists()) {
            dataFolder.getParentFile().mkdirs();
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());

        // Enable WAL mode for better concurrent read/write performance
        try (Statement walStmt = connection.createStatement()) {
            walStmt.execute("PRAGMA journal_mode=WAL;");
        }

        createTables();
        return true;
    }

    private boolean initializeMySQL() throws SQLException {
        FileConfiguration config = plugin.getConfig();
        String host = config.getString("database.mysql.host", "localhost");
        int port = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "aurelium");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database + "?autoReconnect=true&useSSL=false";
        connection = DriverManager.getConnection(url, username, password);
        
        createTables();
        return true;
    }

    private void createTables() {
        String autoIncrement = "mysql".equals(databaseType) ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        
        try (Statement statement = connection.createStatement()) {
            // Players table
            statement.execute("CREATE TABLE IF NOT EXISTS players (" +
                    "uuid VARCHAR(36) PRIMARY KEY, " +
                    "name VARCHAR(16), " +
                    "balance DOUBLE NOT NULL DEFAULT 0.0, " +
                    "gui_style VARCHAR(16) DEFAULT 'MODERN'" +
                    ");");
            // Migration for existing tables
            addColumnIfNotExists("players", "gui_style", "VARCHAR(16) DEFAULT 'MODERN'");

            // Auctions table
            statement.execute("CREATE TABLE IF NOT EXISTS auctions (" +
                    "id " + autoIncrement + ", " +
                    "seller_uuid VARCHAR(36), " +
                    "item_data TEXT, " + // Serialized item
                    "price DOUBLE, " +
                    "is_bin BOOLEAN, " +
                    "expiration LONG, " +
                    "highest_bidder_uuid VARCHAR(36), " +
                    "ended BOOLEAN DEFAULT 0, " +
                    "collected BOOLEAN DEFAULT 0, " +
                    "listing_fee DOUBLE DEFAULT 0.0, " +
                    "start_time LONG" +
                    ");");
            addColumnIfNotExists("auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
            addColumnIfNotExists("auctions", "start_time", "LONG");

            // Offline Earnings table
            statement.execute("CREATE TABLE IF NOT EXISTS offline_earnings (" +
                    "id " + autoIncrement + ", " +
                    "uuid VARCHAR(36), " +
                    "amount DOUBLE, " +
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
                    "status VARCHAR(16) DEFAULT 'ACTIVE'" +
                    ");");

            createOffersTable(autoIncrement);

        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not create tables for " + databaseType + "!", e);
        }
    }

    private void createOffersTable(String autoIncrement) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auction_offers (" +
                    "id " + autoIncrement + ", " +
                    "auction_id INTEGER, " +
                    "bidder_uuid VARCHAR(36), " +
                    "amount DOUBLE, " +
                    "status VARCHAR(16) DEFAULT 'PENDING', " +
                    "timestamp LONG, " +
                    "FOREIGN KEY(auction_id) REFERENCES auctions(id)" +
                    ");");
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not create offers table for " + databaseType + "!", e);
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

    private void addColumnIfNotExists(String table, String column, String type) {
        try (Statement statement = connection.createStatement()) {
            // Check if column exists
            try {
                statement.executeQuery("SELECT " + column + " FROM " + table + " LIMIT 1");
                return; // Column exists
            } catch (SQLException e) {
                // Column likely doesn't exist
            }

            // Add column
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            // Ignore dialect specific info prints for cleaner console output 
        } catch (SQLException e) {
            // Silently fail if column manipulation isn't supported on backend
        }
    }

}

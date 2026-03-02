package com.aureleconomy.database;

import com.aureleconomy.AurelEconomy;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {

    private final AurelEconomy plugin;
    private Connection connection;

    public DatabaseManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    public boolean initialize() {
        try {
            File dataFolder = new File(plugin.getDataFolder(), "database.db");
            if (!dataFolder.getParentFile().exists()) {
                dataFolder.getParentFile().mkdirs();
            }

            // Class.forName("org.sqlite.JDBC"); // Not needed if loaded via libraries, but
            // good for safety
            connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());

            // Enable WAL mode for better concurrent read/write performance
            try (Statement walStmt = connection.createStatement()) {
                walStmt.execute("PRAGMA journal_mode=WAL;");
            }

            createTables();
            return true;
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not initialize database!", e);
            return false;
        }
    }

    private void createTables() {
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
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
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
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "uuid VARCHAR(36), " +
                    "amount DOUBLE, " +
                    "item_display VARCHAR(64), " +
                    "timestamp LONG" +
                    ");");

            // Buy Orders table
            statement.execute("CREATE TABLE IF NOT EXISTS buy_orders (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "buyer_uuid VARCHAR(36), " +
                    "material VARCHAR(64), " +
                    "amount_requested INTEGER, " +
                    "amount_filled INTEGER DEFAULT 0, " +
                    "price_per_piece DOUBLE, " +
                    "status VARCHAR(16) DEFAULT 'ACTIVE'" +
                    ");");

            createOffersTable();

        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not create tables!", e);
        }
    }

    private void createOffersTable() {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auction_offers (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "auction_id INTEGER, " +
                    "bidder_uuid VARCHAR(36), " +
                    "amount DOUBLE, " +
                    "status VARCHAR(16) DEFAULT 'PENDING', " +
                    "timestamp LONG, " +
                    "FOREIGN KEY(auction_id) REFERENCES auctions(id)" +
                    ");");
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not create offers table!", e);
        }
    }

    public Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                File dataFolder = new File(plugin.getDataFolder(), "database.db");
                connection = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.getAbsolutePath());
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Failed to re-establish database connection!", e);
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
            plugin.getComponentLogger().info("Added column " + column + " to table " + table);
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Failed to add column " + column + " to " + table, e);
        }
    }

}

package com.aureleconomy.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.impl.Database;
import com.aureleconomy.database.impl.MySQLDatabase;
import com.aureleconomy.database.impl.SQLiteDatabase;
import com.aureleconomy.database.repositories.PlayerRepository;
import com.aureleconomy.database.schema.AuctionOfferSchema;
import com.aureleconomy.database.schema.AuctionSchema;
import com.aureleconomy.database.schema.BuyOrderSchema;
import com.aureleconomy.database.schema.CustomItemSchema;
import com.aureleconomy.database.schema.DatabaseInfoSchema;
import com.aureleconomy.database.schema.OfflineEarningSchema;
import com.aureleconomy.database.schema.PlayerBalanceSchema;
import com.aureleconomy.database.schema.PlayerSchema;
import com.aureleconomy.database.schema.PriceHistorySchema;
import com.aureleconomy.database.schema.Schema;
import com.aureleconomy.database.types.SQLTypes;

/**
 * Owns the plugin's database connection, schema and migrations.
 *
 * <p>The connection is opened by {@link #initialize()}, not by the constructor —
 * {@code AurelEconomy#onEnable} takes a backup of the database file in between.
 */
public class DatabaseManager {

    private static final int LATEST_SCHEMA_VERSION = 3;

    private final AurelEconomy plugin;
    private final DatabaseSettings settings;

    /** Lock for serializing all async DB writes to prevent concurrent Connection use. */
    private final Object dbWriteLock = new Object();

    private Database database;
    private List<Schema> schemas;
    private boolean legacyBalancesChecked = false;

    public DatabaseManager(AurelEconomy plugin) {
        this.plugin = plugin;
        this.settings = readSettings();
    }

    // -------------------- Configuration --------------------

    private DatabaseSettings readSettings() {
        FileConfiguration config = plugin.getConfig();
        String configuredType = config.getString("database.type", "sqlite");
        DatabaseType type = DatabaseType.fromConfig(configuredType);

        if (!type.getValue().equalsIgnoreCase(configuredType.trim())
                && !"mariadb".equalsIgnoreCase(configuredType.trim())) {
            plugin.getComponentLogger().warn("Unknown database.type '" + configuredType
                    + "' — falling back to " + type.getValue() + ". Supported values: sqlite, mysql, mariadb.");
        }

        return new DatabaseSettings(
                type,
                config.getString("database.file", "database.db"),
                config.getString("database.mysql.host", "localhost"),
                config.getInt("database.mysql.port", 3306),
                config.getString("database.mysql.database", "aurelium"),
                config.getString("database.mysql.username", "root"),
                config.getString("database.mysql.password", ""));
    }

    /** Returns true if the configured database type is MySQL/MariaDB. */
    public boolean isMySQL() {
        return settings.getType() == DatabaseType.MYSQL;
    }

    /**
     * Returns the lock object for serializing async DB write operations.
     * All async tasks that use getConnection() for writes should synchronize on this.
     *
     * <pre>
     * synchronized (dbManager.getWriteLock()) {
     *     try (PreparedStatement ps = dbManager.getConnection().prepareStatement(...)) { ... }
     * }
     * </pre>
     */
    public Object getWriteLock() {
        return dbWriteLock;
    }

    // -------------------- Lifecycle --------------------

    public boolean initialize() {
        try {
            database = switch (settings.getType()) {
                case MYSQL -> new MySQLDatabase(settings);
                case SQLITE -> new SQLiteDatabase(plugin, settings);
            };
            database.connect();

            SQLTypes t = database.getTypes();
            // Order matters: players before player_balances (the legacy balance migration reads
            // both), auctions before auction_offers (foreign key).
            schemas = List.of(
                    new DatabaseInfoSchema(t),
                    new PlayerSchema(t),
                    new PlayerBalanceSchema(t),
                    new AuctionSchema(t),
                    new AuctionOfferSchema(t),
                    new OfflineEarningSchema(t),
                    new BuyOrderSchema(t),
                    new PriceHistorySchema(t),
                    new CustomItemSchema(t));

            createTables();
            migrateLegacyBalances();
            runMigrations();

            plugin.getComponentLogger().info("Connected to " + settings.getType().getValue() + " database.");
            return true;
        } catch (SQLException e) {
            plugin.getComponentLogger().error(
                    "Could not initialize database (" + settings.getType().getValue() + ")!", e);
            return false;
        }
    }

    public synchronized Connection getConnection() {
        try {
            return database.getConnection();
        } catch (SQLException e) {
            plugin.getComponentLogger().error(
                    "Failed to re-establish " + settings.getType().getValue() + " database connection!", e);
            return null;
        }
    }

    public synchronized void close() {
        if (database == null) {
            return;
        }
        try {
            database.disconnect();
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Could not close database connection!", e);
        }
    }

    public void backupDatabase(String version) {
        if (settings.getType() != DatabaseType.SQLITE) {
            return;
        }

        File dbFile = new File(plugin.getDataFolder(), settings.getSqliteFile());
        if (!dbFile.exists()) {
            return;
        }

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

    // -------------------- Schema --------------------

    private void createTables() throws SQLException {
        for (Schema schema : schemas) {
            try {
                database.execute(schema.create());
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Could not create table '" + schema.getTable()
                        + "' for " + settings.getType().getValue() + "!", e);
                throw e; // Propagate so initialize() returns false instead of silently succeeding
            }
        }
    }

    /**
     * Drops every table. Not called by the plugin — kept for tooling and tests that need a
     * clean slate.
     */
    public void dropTables() throws SQLException {
        // Reverse order so tables are removed before the ones they reference.
        for (int i = schemas.size() - 1; i >= 0; i--) {
            database.execute(schemas.get(i).drop());
        }
    }

    // -------------------- Migrations --------------------

    private void runMigrations() {
        int currentVersion = getDatabaseVersion();
        if (currentVersion >= LATEST_SCHEMA_VERSION)
            return;

        plugin.getComponentLogger().info("Database outdated (v" + currentVersion
                + "). Starting automatic migration to v" + LATEST_SCHEMA_VERSION + "...");

        Connection connection = getConnection();
        if (connection == null) {
            plugin.getComponentLogger().error("Database migration FAILED: no connection available.");
            return;
        }

        try {
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
                /* ignored */
            }
            plugin.getComponentLogger().error("Database migration FAILED! Some features might be broken.", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                /* ignored */
            }
        }
    }

    private int getDatabaseVersion() {
        try (Statement statement = getConnection().createStatement();
                ResultSet rs = statement.executeQuery("SELECT version FROM database_info LIMIT 1")) {
            if (rs.next())
                return rs.getInt("version");
        } catch (SQLException e) {
        }
        return 0;
    }

    private void updateDatabaseVersion(int version) throws SQLException {
        try (Statement statement = getConnection().createStatement()) {
            statement.execute("DELETE FROM database_info;");
            statement.execute("INSERT INTO database_info (version) VALUES (" + version + ");");
        }
    }

    private void applyMigration(int version) throws SQLException {
        switch (version) {
            case 1:
                addColumnIfNotExists("players", "gui_style", "VARCHAR(16) DEFAULT 'MODERN'");
                addColumnIfNotExists("auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
                addColumnIfNotExists("auctions", "start_time", "LONG");
                addColumnIfNotExists("auctions", "currency", "VARCHAR(32)");
                addColumnIfNotExists("offline_earnings", "currency", "VARCHAR(32)");
                addColumnIfNotExists("buy_orders", "currency", "VARCHAR(32)");
                addColumnIfNotExists("auction_offers", "currency", "VARCHAR(32)");
                break;
            case 2:
                // Fix: propagate DDL failure — throw instead of swallowing
                database.execute(new CustomItemSchema(database.getTypes()).create());
                break;
            case 3:
                addColumnIfNotExists("auctions", "purchase_mode", "VARCHAR(16) DEFAULT 'STACK'");
                break;
        }
    }

    private void addColumnIfNotExists(String table, String column, String type) throws SQLException {
        try (Statement statement = getConnection().createStatement()) {
            try (ResultSet ignored = statement.executeQuery("SELECT " + column + " FROM " + table + " LIMIT 1")) {
                return;
            } catch (SQLException e) {
                // Column (or table) missing — fall through and add it.
            }
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /**
     * Moves balances from the pre-multi-currency {@code players.balance} column into
     * {@code player_balances}. A no-op on databases that never had that column.
     */
    private void migrateLegacyBalances() {
        if (legacyBalancesChecked)
            return;
        legacyBalancesChecked = true;

        try (Statement statement = getConnection().createStatement()) {
            try (ResultSet ignored = statement.executeQuery("SELECT balance FROM players LIMIT 1")) {
                // Legacy column present — migrate below.
            }

            plugin.getComponentLogger()
                    .info("Legacy single-currency database detected. Migrating to multi-currency system...");
            String defaultCurrency = resolveDefaultCurrency();

            try (PreparedStatement ps = getConnection().prepareStatement(
                    "INSERT INTO player_balances (uuid, currency, balance) " +
                            "SELECT uuid, ?, balance FROM players " +
                            "WHERE uuid NOT IN (SELECT uuid FROM player_balances WHERE currency = ?);")) {
                ps.setString(1, defaultCurrency);
                ps.setString(2, defaultCurrency);
                ps.executeUpdate();
            }

            try {
                statement.execute("ALTER TABLE players DROP COLUMN balance;");
            } catch (SQLException dropError) {
                // SQLite versions before 3.35 cannot drop columns; leaving it is harmless.
            }

            plugin.getComponentLogger().info("Multi-currency database migration completed successfully.");
        } catch (SQLException e) {
            // No legacy balance column — nothing to migrate.
        }
    }

    /**
     * Mirrors EconomyManager#getDefaultCurrency, reading straight from config. The
     * EconomyManager does not exist yet while the database is initializing.
     */
    private String resolveDefaultCurrency() {
        FileConfiguration config = plugin.getConfig();
        var currencies = config.getConfigurationSection("economy.currencies");

        String configured = config.getString("economy.default-currency", "");
        if (currencies != null && currencies.contains(configured)) {
            return configured;
        }
        if (currencies != null && !currencies.getKeys(false).isEmpty()) {
            return currencies.getKeys(false).iterator().next();
        }
        return configured;
    }

    // -------------------- Repositories --------------------

    public PlayerRepository players() {
        return new PlayerRepository(database);
    }
}

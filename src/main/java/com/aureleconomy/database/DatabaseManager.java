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
import com.aureleconomy.database.schema.LedgerSchema;
import com.aureleconomy.database.schema.OfflineEarningSchema;
import com.aureleconomy.database.schema.PlayerBalanceSchema;
import com.aureleconomy.database.schema.PlayerSchema;
import com.aureleconomy.database.schema.PriceHistorySchema;
import com.aureleconomy.database.schema.Schema;
import com.aureleconomy.database.schema.TradeVolumeSchema;
import com.aureleconomy.database.types.SQLTypes;

/**
 * Owns the plugin's database connection, schema and migrations.
 *
 * <p>The connection is opened by {@link #initialize()}, not by the constructor —
 * {@code AurelEconomy#onEnable} takes a backup of the database file in between.
 */
public class DatabaseManager {

    private static final int LATEST_SCHEMA_VERSION = 7;

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
                config.getString("database.mysql.password", ""),
                readSslMode(config));
    }

    private DatabaseSettings.SslMode readSslMode(FileConfiguration config) {
        String configured = config.getString("database.mysql.ssl-mode", "PREFERRED");
        DatabaseSettings.SslMode mode = DatabaseSettings.SslMode.fromConfig(configured);
        if (mode == null) {
            plugin.getComponentLogger().warn("Unknown database.mysql.ssl-mode '" + configured
                    + "' — falling back to PREFERRED. Supported values: DISABLED, PREFERRED,"
                    + " REQUIRED, VERIFY_CA, VERIFY_IDENTITY.");
            return DatabaseSettings.SslMode.PREFERRED;
        }
        return mode;
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
                    new TradeVolumeSchema(t),
                    new LedgerSchema(t),
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

    /**
     * Borrows a connection from the pool. The caller owns it: closing it returns it
     * to the pool, and not closing it leaks a slot.
     *
     * <p>The null-on-failure contract is deliberate and unchanged. A failed borrow,
     * including a pool timeout, is logged with its cause and reported as null; the
     * call sites were written against that shape, and several treat a null as a
     * refused operation rather than letting an exception escape. The pool timeout
     * is a SQLException like any other here, so a leaked slot surfaces through the
     * same path as an unreachable server.
     */
    public Connection getConnection() {
        try {
            return database.getConnection();
        } catch (SQLException e) {
            plugin.getComponentLogger().error(
                    "Failed to borrow a " + settings.getType().getValue() + " database connection!", e);
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

        File dbFile;
        try {
            dbFile = SQLiteDatabase.resolveDatabaseFile(plugin.getDataFolder(), settings.getSqliteFile());
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Skipping database backup: " + e.getMessage());
            return;
        }
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
                applyMigration(connection, i);
            }

            updateDatabaseVersion(connection, LATEST_SCHEMA_VERSION);
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
            // Returned to the pool, not closed: transferring the connection out of
            // the pool for good is what the pool exists to prevent. The rollback
            // above has already run by this point, so a failed migration cannot be
            // committed by the reset.
            try {
                connection.close();
            } catch (SQLException ex) {
                /* ignored */
            }
        }
    }

    private int getDatabaseVersion() {
        Connection conn = getConnection();
        if (conn == null) {
            return 0;
        }
        try (conn;
                Statement statement = conn.createStatement();
                ResultSet rs = statement.executeQuery("SELECT version FROM database_info LIMIT 1")) {
            if (rs.next())
                return rs.getInt("version");
        } catch (SQLException e) {
            // database_info is missing or empty — treat the database as pre-versioning (v0).
        }
        return 0;
    }

    private void updateDatabaseVersion(Connection conn, int version) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute("DELETE FROM database_info;");
            statement.execute("INSERT INTO database_info (version) VALUES (" + version + ");");
        }
    }

    private void applyMigration(Connection conn, int version) throws SQLException {
        switch (version) {
            case 1:
                addColumnIfNotExists(conn, "players", "gui_style", "VARCHAR(16) DEFAULT 'MODERN'");
                addColumnIfNotExists(conn, "auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
                addColumnIfNotExists(conn, "auctions", "start_time", database.getTypes().time());
                addColumnIfNotExists(conn, "auctions", "currency", "VARCHAR(32)");
                addColumnIfNotExists(conn, "offline_earnings", "currency", "VARCHAR(32)");
                addColumnIfNotExists(conn, "buy_orders", "currency", "VARCHAR(32)");
                addColumnIfNotExists(conn, "auction_offers", "currency", "VARCHAR(32)");
                break;
            case 2:
                // Fix: propagate DDL failure — throw instead of swallowing
                execute(conn, new CustomItemSchema(database.getTypes()).create());
                break;
            case 3:
                addColumnIfNotExists(conn, "auctions", "purchase_mode", "VARCHAR(16) DEFAULT 'STACK'");
                break;
            case 4:
                // MySQL/MariaDB alias LONG to MEDIUMTEXT, so timestamp columns written by
                // earlier versions hold epoch millis as text. Convert them to BIGINT.
                convertTimeColumnToBigInt(conn, "auctions", "expiration");
                convertTimeColumnToBigInt(conn, "auctions", "start_time");
                convertTimeColumnToBigInt(conn, "auction_offers", "timestamp");
                convertTimeColumnToBigInt(conn, "offline_earnings", "timestamp");
                convertTimeColumnToBigInt(conn, "price_history", "timestamp");
                break;
            case 5:
                // Per-trade log backing the dashboard's volume column and sort.
                execute(conn, new TradeVolumeSchema(database.getTypes()).create());
                break;
            case 6:
                // The dashboard reads the price as it was 24h ago, and both of
                // these tables are pruned by time. Without these indexes each is
                // a full table scan, and price_history grows by one row per
                // tradeable item on every snapshot.
                addIndexIfNotExists(conn, "price_history", "idx_price_history_timestamp", "timestamp");
                addIndexIfNotExists(conn, "trade_volume", "idx_trade_volume_trade_at", "trade_at");
                break;
            case 7:
                // F1 and F4 ride together on purpose: the ledger table and the
                // money-column retype are one schema change, so a server is
                // backed up, migrated and verified once rather than twice.
                execute(conn, new LedgerSchema(database.getTypes()).create());
                // History is read newest-first for one player, and the audit query
                // sums by time; the pair covers both without a second index.
                addIndexIfNotExists(conn, "transaction_ledger", "idx_transaction_ledger_actor", "actor_uuid, ts");
                // Every join asks for this player's unclaimed earnings (M14/L4).
                addIndexIfNotExists(conn, "offline_earnings", "idx_offline_earnings_uuid", "uuid");
                retypeMoneyColumns(conn);
                break;
        }
    }

    /**
     * Runs one migration statement on the connection the migration transaction
     * already holds. Borrowing a second connection here would deadlock against the
     * single-connection SQLite pool.
     */
    private static void execute(Connection conn, String sql) throws SQLException {
        try (Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    private void addColumnIfNotExists(Connection conn, String table, String column, String type)
            throws SQLException {
        try (Statement statement = conn.createStatement()) {
            if (columnExists(statement, table, column)) {
                return;
            }
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    /**
     * Retypes a timestamp column to BIGINT on MySQL/MariaDB. A no-op on SQLite, where
     * {@code LONG} already carries numeric affinity, and on columns that do not exist.
     */
    private void convertTimeColumnToBigInt(Connection conn, String table, String column) throws SQLException {
        if (settings.getType() != DatabaseType.MYSQL) {
            return;
        }
        try (Statement statement = conn.createStatement()) {
            if (!columnExists(statement, table, column)) {
                return;
            }
            statement.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " BIGINT");
        }
    }

    /** Money columns F4 retypes, in the order they are altered. */
    private static final String[][] MONEY_COLUMNS = {
            {"player_balances", "balance"},
            {"auctions", "price"},
            {"auctions", "listing_fee"},
            {"auction_offers", "amount"},
            {"buy_orders", "price_per_piece"},
            {"offline_earnings", "amount"},
            {"price_history", "buy_price"},
            {"price_history", "sell_price"},
    };

    /**
     * F4: retypes money columns to DECIMAL(18,2).
     *
     * <p>MySQL only, on purpose. SQLite stores a DECIMAL as a REAL through NUMERIC
     * affinity, so retyping a column there would rewrite every money table on every
     * server for a type name that changes nothing; the money write path already
     * normalises with {@code setScale(2)}. MySQL stores DECIMAL exactly, which is
     * the fix that stops doubling drift compounding in a balance.
     *
     * <p>A bare {@code MODIFY} would silently drop NOT NULL and the default, so the
     * current definition is read back from INFORMATION_SCHEMA and kept.
     */
    private void retypeMoneyColumns(Connection conn) throws SQLException {
        if (settings.getType() != DatabaseType.MYSQL) {
            return;
        }
        for (String[] moneyColumn : MONEY_COLUMNS) {
            retypeMoneyColumn(conn, moneyColumn[0], moneyColumn[1]);
        }
    }

    private void retypeMoneyColumn(Connection conn, String table, String column) throws SQLException {
        String nullable;
        String defaultValue;
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT IS_NULLABLE, COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            select.setString(1, table);
            select.setString(2, column);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    return; // this install does not have that column
                }
                nullable = rs.getString(1);
                defaultValue = rs.getString(2);
            }
        }

        try (Statement statement = conn.createStatement()) {
            statement.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " "
                    + moneyColumnDefinition(nullable, defaultValue));
        }
    }

    /**
     * The MODIFY definition for a money column, keeping the nullability and default
     * the column already had. Split out so the preservation rule is testable without
     * a MySQL server.
     */
    static String moneyColumnDefinition(String nullable, String defaultValue) {
        StringBuilder definition = new StringBuilder("DECIMAL(18,2)");
        if ("NO".equalsIgnoreCase(nullable)) {
            definition.append(" NOT NULL");
        }
        if (defaultValue != null) {
            definition.append(" DEFAULT ").append(defaultValue);
        }
        return definition.toString();
    }

    /**
     * Adds an index when it is missing.
     *
     * <p>Cannot use {@code CREATE INDEX IF NOT EXISTS}: SQLite accepts it but
     * MySQL rejects the syntax outright, so the index list is probed instead.
     */
    private void addIndexIfNotExists(Connection conn, String table, String index, String columns)
            throws SQLException {
        try (Statement statement = conn.createStatement()) {
            if (indexExists(statement, table, index)) {
                return;
            }
            statement.execute("CREATE INDEX " + index + " ON " + table + " (" + columns + ")");
        }
    }

    private boolean indexExists(Statement statement, String table, String index) throws SQLException {
        boolean mysql = settings.getType() == DatabaseType.MYSQL;
        String sql = mysql
                ? "SHOW INDEX FROM " + table + " WHERE Key_name = '" + index + "'"
                : "PRAGMA index_list(" + table + ")";
        try (ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = mysql ? rs.getString("Key_name") : rs.getString("name");
                if (index.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnExists(Statement statement, String table, String column) {
        try (ResultSet ignored = statement.executeQuery("SELECT " + column + " FROM " + table + " LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            // Column (or table) missing.
            return false;
        }
    }

    /** {@link #getConnection()} has already logged the cause; turn the null into a failure. */
    private Connection requireConnection(String what) throws SQLException {
        Connection conn = getConnection();
        if (conn == null) {
            throw new SQLException("No " + settings.getType().getValue()
                    + " database connection available for " + what + ".");
        }
        return conn;
    }

    /**
     * Moves balances from the pre-multi-currency {@code players.balance} column into
     * {@code player_balances}. A no-op on databases that never had that column.
     */
    private void migrateLegacyBalances() throws SQLException {
        if (legacyBalancesChecked)
            return;
        legacyBalancesChecked = true;

        try (Connection conn = requireConnection("the legacy balance migration");
                Statement statement = conn.createStatement()) {
            // Only the probe may fail silently: a missing balance column means there is nothing
            // to migrate. Everything after it is a real migration step whose failure must surface.
            if (!columnExists(statement, "players", "balance")) {
                return;
            }

            plugin.getComponentLogger()
                    .info("Legacy single-currency database detected. Migrating to multi-currency system...");
            String defaultCurrency = resolveDefaultCurrency();

            try (PreparedStatement ps = conn.prepareStatement(
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
            plugin.getComponentLogger().error(
                    "Migrating legacy single-currency balances FAILED — aborting startup so no "
                            + "balances are lost.", e);
            throw e;
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

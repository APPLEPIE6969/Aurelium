package com.aureleconomy.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import com.aureleconomy.database.schema.ProcessedPurchaseSchema;
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

    private static final int LATEST_SCHEMA_VERSION = 8;

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
                    new CustomItemSchema(t),
                    new ProcessedPurchaseSchema(t));

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

    /**
     * Applies the retention policy (L4): ended and collected auctions, offline
     * earning notifications nobody read, and old database backups. Safe to call
     * off the main thread; the windows come from {@code database.retention.*}
     * and a window of 0 keeps that kind forever.
     *
     * @return how many rows and files were removed
     */
    public int purgeRetention() {
        FileConfiguration config = plugin.getConfig();
        int auctionDays = config.getInt("database.retention.auctions-days", RetentionPolicy.DEFAULT_DAYS);
        int earningDays = config.getInt("database.retention.offline-earnings-days",
                RetentionPolicy.DEFAULT_DAYS);
        int backupDays = config.getInt("database.retention.backups-days", RetentionPolicy.DEFAULT_DAYS);

        long now = System.currentTimeMillis();
        int removed = 0;
        if (auctionDays > 0 || earningDays > 0) {
            try (Connection conn = getConnection()) {
                if (conn != null) {
                    synchronized (dbWriteLock) {
                        if (auctionDays > 0) {
                            removed += RetentionPolicy.purgeAuctions(conn,
                                    RetentionPolicy.cutoff(auctionDays, now));
                        }
                        if (earningDays > 0) {
                            removed += RetentionPolicy.purgeOfflineEarnings(conn,
                                    RetentionPolicy.cutoff(earningDays, now));
                        }
                    }
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().warn("Could not apply the retention policy: " + e.getMessage());
            }
        }
        if (backupDays > 0) {
            removed += RetentionPolicy.purgeBackupFiles(new File(plugin.getDataFolder(), "backups"),
                    RetentionPolicy.cutoff(backupDays, now));
        }
        if (removed > 0) {
            plugin.getComponentLogger().info(
                    "Retention policy removed " + removed + " old rows and backup files.");
        }
        return removed;
    }

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

            // What the transaction is worth depends on the backend (M11). SQLite runs
            // DDL inside a transaction, so a failure here rolls every step back. MySQL
            // commits each ALTER and CREATE implicitly, so the rollback cannot undo the
            // steps that already ran; what it does guarantee is that the version row is
            // not advanced, and every step is written to be re-runnable: columns and
            // indexes are probed before they are created, tables carry IF NOT EXISTS,
            // and the v4 timestamp conversion normalises before it retypes. A failed
            // migration on MySQL therefore resumes from where it stopped on the next
            // start, rather than needing a restored backup.
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
            case 8:
                // H9: a purchase ID the dashboard already sent has to be
                // answered from the database, not from a ten-minute in-memory
                // map. The row is claimed before the purchase executes, so a
                // restart cannot replay it.
                execute(conn, new ProcessedPurchaseSchema(database.getTypes()).create());
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
     *
     * <p>M11: the retype cannot be the first thing that happens to the column. A
     * server that ran an earlier release holds epoch millis as text, but it can also
     * hold the shapes those releases accepted, and MySQL will not convert a datetime
     * string to BIGINT: under a strict sql_mode the ALTER fails and the migration
     * stops half way, and without strict mode it keeps the year and silently rewrites
     * every timestamp. The values are normalised first, so the ALTER only ever sees
     * digits.
     */
    private void convertTimeColumnToBigInt(Connection conn, String table, String column) throws SQLException {
        if (settings.getType() != DatabaseType.MYSQL) {
            return;
        }
        try (Statement statement = conn.createStatement()) {
            if (!columnExists(statement, table, column)) {
                return;
            }
            for (String sql : timeColumnConversionStatements(table, column)) {
                statement.execute(sql);
            }
        }
    }

    /**
     * The statements that turn text timestamps into epoch millis before the column is
     * retyped (M11).
     *
     * <p>Two passes on purpose. The first converts the datetimes earlier releases
     * wrote, in milliseconds like the rest of the schema. The second catches whatever
     * is left that is still not a number, so the ALTER cannot fail and cannot keep
     * only the leading digits: an unparseable timestamp becomes 0, which is the
     * "never set" value the rest of the schema already uses, rather than a date in
     * the year 2025 pretending to be epoch millis.
     *
     * <p>The retype is part of the same list so the order is the method's contract,
     * not a call-site convention: a normalisation that is written but not run before
     * the ALTER fixes nothing.
     *
     * <p>Split out so the shape and the order are testable without a MySQL server.
     */
    static List<String> timeColumnConversionStatements(String table, String column) {
        List<String> statements = new ArrayList<>(normaliseTextTimestamps(table, column));
        statements.add("ALTER TABLE " + table + " MODIFY COLUMN " + column + " BIGINT");
        return statements;
    }

    static List<String> normaliseTextTimestamps(String table, String column) {
        String target = "`" + table + "`";
        String value = "`" + column + "`";
        String notANumber = value + " IS NOT NULL AND " + value + " NOT REGEXP '^[0-9]+$'";
        return List.of(
                "UPDATE " + target + " SET " + value + " = CAST(UNIX_TIMESTAMP(" + value + ") AS SIGNED) * 1000"
                        + " WHERE " + notANumber + " AND UNIX_TIMESTAMP(" + value + ") IS NOT NULL",
                "UPDATE " + target + " SET " + value + " = 0 WHERE " + notANumber);
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

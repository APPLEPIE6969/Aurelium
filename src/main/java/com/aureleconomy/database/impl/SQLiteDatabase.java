package com.aureleconomy.database.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseSettings;
import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.types.SQLTypes;
import com.aureleconomy.database.types.SQLiteTypes;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * SQLite through a HikariCP pool of exactly one connection.
 *
 * <p>One, not more: SQLite permits a single writer at a time, so a larger pool
 * would only replace the plugin's own write lock with {@code SQLITE_BUSY}
 * retries. Keeping one connection also means a slot that is never returned is a
 * deterministic timeout on the next borrow instead of an intermittent failure,
 * which is what made the old shared handle so hard to see.
 *
 * <p>Every physical connection is created with the same PRAGMAs. They are handed
 * to the driver as connection properties rather than as a {@code PRAGMA}
 * statement, because the pool may replace the connection at any time
 * ({@code maxLifetime} and a broken socket both do) and a one-off statement in
 * {@link #connect()} would leave the replacement without them.
 */
public class SQLiteDatabase implements Database {

    /** The pool holds one connection; see the class comment. */
    private static final int POOL_SIZE = 1;

    /**
     * How long a borrow waits before giving up. A normal path never reaches it:
     * every borrow is short and the money writers serialise on the plugin's write
     * lock before they borrow. Reaching it means a connection was not returned,
     * and the log says so in fifteen seconds rather than thirty.
     */
    private static final long CONNECTION_TIMEOUT_MS = 15_000L;

    /** Log a stack trace when a borrow is held longer than this. */
    private static final long LEAK_DETECTION_THRESHOLD_MS = 60_000L;

    private final AurelEconomy plugin;
    private final DatabaseSettings settings;
    private final SQLiteTypes types = new SQLiteTypes();
    private HikariDataSource dataSource;

    public SQLiteDatabase(AurelEconomy plugin, DatabaseSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void connect() throws SQLException {
        if (isConnected()) {
            return;
        }

        File dbFile = resolveDatabaseFile(plugin.getDataFolder(), settings.getSqliteFile());
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create database directory: " + parent);
        }
        requireInsideDataFolder(parent);

        HikariConfig config = new HikariConfig();
        config.setPoolName("Aurelium-SQLite");
        // Named rather than left to DriverManager: the driver comes from the
        // plugin.yml libraries list, and asking for it by name reports a missing
        // or renamed library as a startup failure instead of a borrow-time
        // "no suitable driver".
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(POOL_SIZE);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);
        // Applied by the driver to every connection it opens, including the ones
        // the pool opens to replace a retired connection.
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("busy_timeout", "30000");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("cache_size", "-10000");

        try {
            // Fails fast: the constructor opens and validates one connection, so a
            // bad path or an unusable PRAGMA is reported here rather than on the
            // first query.
            dataSource = new HikariDataSource(config);
        } catch (RuntimeException e) {
            // Hikari reports startup failures as a RuntimeException. initialize()
            // treats a SQLException as "this database is unusable" and disables the
            // plugin, so translate rather than crash out of onEnable.
            throw new SQLException("Could not open the SQLite connection pool at " + dbFile, e);
        }
    }

    /**
     * Resolves {@code database.file} beneath the plugin data folder. The config value has to name
     * a file inside that folder: absolute paths and anything that climbs out of it via {@code ..}
     * are rejected rather than silently writing the database somewhere else on the host.
     */
    public static File resolveDatabaseFile(File pluginFolder, String configured) throws SQLException {
        if (configured == null || configured.isBlank()) {
            throw new SQLException("database.file must not be empty.");
        }

        Path dataFolder = pluginFolder.toPath().toAbsolutePath().normalize();
        Path resolved;
        try {
            Path candidate = Path.of(configured);
            if (candidate.isAbsolute()) {
                throw new SQLException("database.file must be relative to the plugin folder: " + configured);
            }
            resolved = dataFolder.resolve(candidate).normalize();
        } catch (InvalidPathException e) {
            throw new SQLException("database.file is not a valid path: " + configured, e);
        }

        if (!resolved.startsWith(dataFolder) || resolved.equals(dataFolder)) {
            throw new SQLException("database.file must stay inside the plugin folder: " + configured);
        }
        return resolved.toFile();
    }

    /**
     * Second guard, run once the directory exists: {@link #resolveDatabaseFile(File, String)}
     * works on the textual path, this one resolves symlinks so a link inside the data folder
     * cannot point the database at an arbitrary location.
     */
    private void requireInsideDataFolder(File parent) throws SQLException {
        if (parent == null || !parent.exists()) {
            return;
        }
        try {
            Path realParent = parent.toPath().toRealPath();
            Path realDataFolder = plugin.getDataFolder().toPath().toRealPath();
            if (!realParent.startsWith(realDataFolder)) {
                throw new SQLException("database.file resolves outside the plugin folder: " + realParent);
            }
        } catch (IOException e) {
            throw new SQLException("Could not verify the database file location.", e);
        }
    }

    /**
     * Borrows the pooled connection. Closing the returned handle returns it to the
     * pool; it does not close the database. A borrow that times out surfaces as a
     * {@link SQLException}, which {@code DatabaseManager.getConnection()} turns
     * into its documented null.
     */
    @Override
    public Connection getConnection() throws SQLException {
        connect();
        // Captured rather than read twice: a concurrent disconnect() during plugin
        // shutdown would otherwise leave the second read null and surface as a
        // NullPointerException instead of the timeout-or-closed SQLException.
        HikariDataSource pool = dataSource;
        if (pool == null) {
            throw new SQLException("The SQLite connection pool was closed.");
        }
        return pool.getConnection();
    }

    @Override
    public void execute(String sql) throws SQLException {
        try (Connection connection = getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public boolean isConnected() {
        if (dataSource == null) {
            return false;
        }
        if (dataSource.isRunning()) {
            return true;
        }
        // Closed by disconnect() or by a failed startup: report the truth and let
        // connect() build a new pool on the next borrow.
        dataSource = null;
        return false;
    }

    @Override
    public void disconnect() throws SQLException {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    @Override
    public SQLTypes getTypes() {
        return types;
    }

    @Override
    public DatabaseType getType() {
        return DatabaseType.SQLITE;
    }
}

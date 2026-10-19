package com.aureleconomy.database.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import com.aureleconomy.database.DatabaseSettings;
import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.types.MySQLTypes;
import com.aureleconomy.database.types.SQLTypes;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * MySQL and MariaDB, both via the shaded MySQL Connector/J driver, through a
 * HikariCP pool.
 *
 * <p>The pool replaces the old cached single connection and its
 * {@code SELECT 1} probe. Hikari validates with {@link Connection#isValid(int)}
 * on borrow and on its idle checks, retires connections at {@code maxLifetime},
 * and pings idle ones with {@code keepaliveTime}; the hand-written probe and its
 * thirty-second rate limit were a local approximation of exactly that.
 */
public class MySQLDatabase implements Database {

    /**
     * Enough for the async writers and dashboard reads to overlap without
     * queueing, while staying far below a MySQL server's default connection cap.
     */
    private static final int POOL_SIZE = 6;

    /** How long a borrow waits before reporting that no connection became free. */
    private static final long CONNECTION_TIMEOUT_MS = 30_000L;

    /** How long a validation probe may take before the connection is discarded. */
    private static final long VALIDATION_TIMEOUT_MS = 5_000L;

    /** Log a stack trace when a borrow is held longer than this. */
    private static final long LEAK_DETECTION_THRESHOLD_MS = 60_000L;

    /** Ping an idle connection before the server's {@code wait_timeout} can drop it. */
    private static final long KEEPALIVE_TIME_MS = 120_000L;

    private final DatabaseSettings settings;
    private final MySQLTypes types = new MySQLTypes();
    private HikariDataSource dataSource;

    public MySQLDatabase(DatabaseSettings settings) {
        this.settings = settings;
    }

    @Override
    public void connect() throws SQLException {
        if (isConnected()) {
            return;
        }

        // sslMode is configurable (database.mysql.ssl-mode); it defaults to PREFERRED, so the
        // connection is encrypted whenever the server offers TLS. VERIFY_CA / VERIFY_IDENTITY
        // additionally validate the server certificate.
        String url = "jdbc:mysql://" + settings.getHost() + ":" + settings.getPort() + "/"
                + settings.getDatabase()
                + "?sslMode=" + settings.getSslMode().name()
                // Keep the server from closing an idle connection out from under us; the
                // driver's own timeouts sit below Hikari's keepalive/lifetime checks.
                + "&connectTimeout=10000"
                + "&socketTimeout=60000"
                + "&tcpKeepAlive=true";

        HikariConfig config = new HikariConfig();
        config.setPoolName("Aurelium-MySQL");
        // Named rather than left to DriverManager: the driver is shaded into the
        // jar, and asking for it by name reports a missing one at startup instead
        // of a borrow-time "no suitable driver".
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(url);
        config.setUsername(settings.getUsername());
        config.setPassword(settings.getPassword());
        config.setMaximumPoolSize(POOL_SIZE);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setValidationTimeout(VALIDATION_TIMEOUT_MS);
        config.setLeakDetectionThreshold(LEAK_DETECTION_THRESHOLD_MS);
        config.setKeepaliveTime(KEEPALIVE_TIME_MS);

        try {
            // Fails fast: the constructor opens and validates one connection, so an
            // unreachable server is reported here rather than on the first query.
            dataSource = new HikariDataSource(config);
        } catch (RuntimeException e) {
            // Hikari reports startup failures as a RuntimeException. initialize()
            // treats a SQLException as "this database is unusable" and disables the
            // plugin, so translate rather than crash out of onEnable.
            throw new SQLException("Could not open the MySQL connection pool at "
                    + settings.getHost() + ":" + settings.getPort(), e);
        }
    }

    /**
     * Borrows a pooled connection. Closing the returned handle returns it to the
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
            throw new SQLException("The MySQL connection pool was closed.");
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
        return DatabaseType.MYSQL;
    }
}

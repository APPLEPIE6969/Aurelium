package com.aureleconomy.database.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.aureleconomy.database.DatabaseSettings;
import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.types.MySQLTypes;
import com.aureleconomy.database.types.SQLTypes;

/**
 * MySQL and MariaDB, both via the shaded MySQL Connector/J driver. The driver registers itself
 * through the JDBC ServiceLoader, so no explicit {@code Class.forName} is needed.
 */
public class MySQLDatabase implements Database {

    /**
     * How long a cached handle is trusted before being probed with {@code SELECT 1}. Short enough
     * that a server-side disconnect is noticed well within a typical {@code wait_timeout}, long
     * enough that a burst of queries does not each pay for a round trip.
     */
    private static final long VALIDATION_INTERVAL_MS = 30_000L;

    private final DatabaseSettings settings;
    private final MySQLTypes types = new MySQLTypes();
    private Connection connection;
    private long lastValidatedAt;

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
                // Keep the server from closing an idle connection out from under us, and let the
                // driver validate/reconnect it on its own. validateConnection() below is the
                // authoritative check; these just reduce how often it has to fire.
                + "&connectTimeout=10000"
                + "&socketTimeout=60000"
                + "&tcpKeepAlive=true";
        connection = DriverManager.getConnection(url, settings.getUsername(), settings.getPassword());
        lastValidatedAt = System.currentTimeMillis();
    }

    @Override
    public Connection getConnection() throws SQLException {
        connect();
        return connection;
    }

    /**
     * Proves the cached handle is still usable, not merely un-closed.
     *
     * <p>{@link Connection#isClosed()} only reports the client's view of the socket. When the
     * server closes an idle connection - MySQL's {@code wait_timeout}, a proxy reset, a container
     * restart - the client still believes it is open, so a plain {@code !isClosed()} check reports
     * healthy and every subsequent query fails with "No operations allowed after connection closed".
     * Issuing a trivial statement is the only reliable way to make the server confirm the socket.
     *
     * <p>The probe costs a round trip, and {@link #getConnection()} runs for nearly every query, so
     * it is rate limited: a handle younger than {@link #VALIDATION_INTERVAL_MS} is trusted. Any
     * query that actually fails still surfaces its own error to the caller.
     *
     * <p>A failed validation discards the handle so {@link #connect()} rebuilds it.
     */
    private void validateConnection() {
        if (connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastValidatedAt != 0 && now - lastValidatedAt < VALIDATION_INTERVAL_MS) {
            return;
        }
        try {
            if (connection.isClosed()) {
                connection = null;
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
            lastValidatedAt = now;
        } catch (SQLException e) {
            // Stale or broken: drop it so the next connect() rebuilds.
            connection = null;
        }
    }

    @Override
    public void execute(String sql) throws SQLException {
        try (Statement statement = getConnection().createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public boolean isConnected() {
        try {
            if (connection == null || connection.isClosed()) {
                connection = null;
                return false;
            }
        } catch (SQLException e) {
            connection = null;
            return false;
        }
        // isClosed() said the client-side handle is fine; confirm with the server.
        validateConnection();
        return connection != null;
    }

    @Override
    public void disconnect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        connection = null;
        lastValidatedAt = 0;
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

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

    private final DatabaseSettings settings;
    private final MySQLTypes types = new MySQLTypes();
    private Connection connection;

    public MySQLDatabase(DatabaseSettings settings) {
        this.settings = settings;
    }

    @Override
    public void connect() throws SQLException {
        if (isConnected()) {
            return;
        }

        String url = "jdbc:mysql://" + settings.getHost() + ":" + settings.getPort() + "/"
                + settings.getDatabase()
                + "?autoReconnect=true&useSSL=false&allowPublicKeyRetrieval=true";
        connection = DriverManager.getConnection(url, settings.getUsername(), settings.getPassword());
    }

    @Override
    public Connection getConnection() throws SQLException {
        connect();
        return connection;
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
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void disconnect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
        connection = null;
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

package com.aureleconomy.database.impl;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseSettings;
import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.types.SQLTypes;
import com.aureleconomy.database.types.SQLiteTypes;

public class SQLiteDatabase implements Database {

    private final AurelEconomy plugin;
    private final DatabaseSettings settings;
    private final SQLiteTypes types = new SQLiteTypes();
    private Connection connection;

    public SQLiteDatabase(AurelEconomy plugin, DatabaseSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    @Override
    public void connect() throws SQLException {
        if (isConnected()) {
            return;
        }

        File dbFile = new File(plugin.getDataFolder(), settings.getSqliteFile());
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=30000;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA cache_size=-10000;");
        }
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
        return DatabaseType.SQLITE;
    }
}

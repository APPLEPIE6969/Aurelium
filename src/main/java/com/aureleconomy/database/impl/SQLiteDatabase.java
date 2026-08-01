package com.aureleconomy.database.impl;

import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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

        File dbFile = resolveDatabaseFile(plugin.getDataFolder(), settings.getSqliteFile());
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Could not create database directory: " + parent);
        }
        requireInsideDataFolder(parent);

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA busy_timeout=30000;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.execute("PRAGMA cache_size=-10000;");
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

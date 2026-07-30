package com.aureleconomy.database;

/**
 * Immutable snapshot of the {@code database} section of config.yml.
 */
public class DatabaseSettings {

    private final DatabaseType type;
    private final String sqliteFile;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;

    public DatabaseSettings(DatabaseType type, String sqliteFile, String host, int port,
            String database, String username, String password) {
        this.type = type;
        this.sqliteFile = sqliteFile;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
    }

    public DatabaseType getType() {
        return type;
    }

    /** Relative file name of the SQLite database inside the plugin data folder. */
    public String getSqliteFile() {
        return sqliteFile;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabase() {
        return database;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}

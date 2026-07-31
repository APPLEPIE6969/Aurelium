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
    private final SslMode sslMode;

    public DatabaseSettings(DatabaseType type, String sqliteFile, String host, int port,
            String database, String username, String password, SslMode sslMode) {
        this.type = type;
        this.sqliteFile = sqliteFile;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.sslMode = sslMode;
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

    /** TLS mode handed to the MySQL driver. Ignored by SQLite. */
    public SslMode getSslMode() {
        return sslMode;
    }

    /**
     * The subset of Connector/J {@code sslMode} values worth exposing in config.yml.
     *
     * <p>{@link #PREFERRED} is the default: it encrypts whenever the server offers TLS, which is
     * what a typical local or private-network MySQL does, without requiring a truststore.
     * {@link #VERIFY_CA} and {@link #VERIFY_IDENTITY} additionally validate the server
     * certificate and are the right choice whenever the database is reached over an untrusted
     * network.
     */
    public enum SslMode {
        DISABLED,
        PREFERRED,
        REQUIRED,
        VERIFY_CA,
        VERIFY_IDENTITY;

        /** Resolves a config value, or {@code null} if it names no known mode. */
        public static SslMode fromConfig(String value) {
            if (value == null) {
                return null;
            }
            for (SslMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return null;
        }
    }
}

package com.aureleconomy.database;

import java.util.Locale;

public enum DatabaseType {
    SQLITE("sqlite", null),
    MYSQL("mysql", 3306);

    private final String value;
    private final Integer port;

    DatabaseType(String value, Integer port) {
        this.value = value;
        this.port = port;
    }

    public String getValue() {
        return value;
    }

    public Integer getDefaultPort() {
        return port;
    }

    /**
     * Resolves a {@code database.type} config value. "mysql" and "mariadb" both map to
     * {@link #MYSQL} — the shaded MySQL Connector/J driver speaks to MariaDB servers too.
     * Unknown values fall back to {@link #SQLITE}, the plugin default.
     */
    public static DatabaseType fromConfig(String value) {
        if (value == null) {
            return SQLITE;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "mysql", "mariadb" -> MYSQL;
            default -> SQLITE;
        };
    }
}

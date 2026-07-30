package com.aureleconomy.database.types;

/**
 * MySQL / MariaDB dialect.
 */
public class MySQLTypes extends SQLTypes {

    @Override
    public String id() {
        return "INT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    public String tableSuffix() {
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public String identifier(int maxLength) {
        return "VARCHAR(" + maxLength + ")";
    }

    @Override
    public String realNumber() {
        return "DOUBLE";
    }

    @Override
    public String flag() {
        return "TINYINT(1)";
    }

    @Override
    public String bigInt() {
        return "BIGINT";
    }
}

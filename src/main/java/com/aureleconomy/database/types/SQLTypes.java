package com.aureleconomy.database.types;

/**
 * SQL column types for a single dialect. The base class holds the SQLite spelling of every
 * type — SQLite is the plugin default, so {@link SQLiteTypes} needs no overrides at all.
 * {@link MySQLTypes} overrides only what actually differs.
 *
 * <p>These types reproduce the schema written by earlier plugin versions verbatim, so that
 * {@code CREATE TABLE IF NOT EXISTS} stays a no-op against an existing database.
 */
public class SQLTypes {

    // -------------------- General types --------------------

    /** Auto-incrementing primary key. */
    public String id() {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    public String integer() {
        return "INTEGER";
    }

    public String decimal() {
        return "DOUBLE";
    }

    public String text() {
        return "TEXT";
    }

    public String uuid() {
        return "VARCHAR(36)";
    }

    /** Minecraft player name. */
    public String name() {
        return "VARCHAR(16)";
    }

    public String varchar(int length) {
        return "VARCHAR(" + length + ")";
    }

    /**
     * Boolean as declared by the auction and order tables. Both dialects accept this spelling,
     * so there is no override.
     */
    public String bool() {
        return "BOOLEAN";
    }

    /**
     * Epoch milliseconds as declared by the auction, offer, earning and price-history tables.
     * SQLite gives {@code LONG} numeric affinity; MySQL aliases it to MEDIUMTEXT, so
     * {@link MySQLTypes#time()} overrides it with BIGINT.
     */
    public String time() {
        return "LONG";
    }

    /** Appended after the closing paren of a CREATE TABLE — storage engine, charset, etc. */
    public String tableSuffix() {
        return "";
    }

    // -------------------- custom_items types --------------------
    // The custom_items table was created with SQLite-native type names (TEXT/REAL/INTEGER)
    // rather than the VARCHAR/DOUBLE/LONG spelling used by the older tables above. These four
    // methods keep that difference intact.

    /** Identifier column: SQLite stores it as TEXT, MySQL needs an explicit length. */
    public String identifier(int maxLength) {
        return "TEXT";
    }

    public String realNumber() {
        return "REAL";
    }

    /** Boolean stored as 0/1. */
    public String flag() {
        return "INTEGER";
    }

    /** 64-bit integer. */
    public String bigInt() {
        return "INTEGER";
    }
}

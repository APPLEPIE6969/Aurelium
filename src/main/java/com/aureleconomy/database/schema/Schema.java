package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * DDL for a single table, rendered in the dialect of the supplied {@link SQLTypes}.
 */
public abstract class Schema {

    protected final SQLTypes t;

    public Schema(SQLTypes t) {
        this.t = t;
    }

    public abstract String create();

    public abstract Table getTable();

    public String drop() {
        return "DROP TABLE IF EXISTS " + getTable() + ";";
    }

    /** DELETE rather than TRUNCATE — SQLite has no TRUNCATE statement. */
    public String truncate() {
        return "DELETE FROM " + getTable() + ";";
    }
}

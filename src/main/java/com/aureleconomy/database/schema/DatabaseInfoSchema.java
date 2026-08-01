package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * Single-row table holding the schema version that drives the migrations in DatabaseManager.
 */
public class DatabaseInfoSchema extends Schema {

    public DatabaseInfoSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "version " + t.integer() + " PRIMARY KEY" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.DATABASE_INFO;
    }
}

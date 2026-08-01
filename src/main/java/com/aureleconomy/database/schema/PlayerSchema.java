package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

public class PlayerSchema extends Schema {

    public PlayerSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "uuid " + t.uuid() + " NOT NULL PRIMARY KEY, " +
                "name " + t.name() + ", " +
                "gui_style " + t.varchar(16) + " DEFAULT 'MODERN'" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.PLAYERS;
    }
}

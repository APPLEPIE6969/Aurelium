package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * One row per (player, currency) pair — the multi-currency balance store.
 */
public class PlayerBalanceSchema extends Schema {

    public PlayerBalanceSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "uuid " + t.uuid() + ", " +
                "currency " + t.varchar(32) + ", " +
                "balance " + t.decimal() + " NOT NULL DEFAULT 0.0, " +
                "PRIMARY KEY (uuid, currency)" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.PLAYER_BALANCES;
    }
}

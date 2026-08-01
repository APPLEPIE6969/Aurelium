package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * Payouts queued while the recipient was offline, shown to them on next join.
 */
public class OfflineEarningSchema extends Schema {

    public OfflineEarningSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "uuid " + t.uuid() + ", " +
                "amount " + t.decimal() + ", " +
                "currency " + t.varchar(32) + ", " +
                "item_display " + t.varchar(64) + ", " +
                "timestamp " + t.time() +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.OFFLINE_EARNINGS;
    }
}

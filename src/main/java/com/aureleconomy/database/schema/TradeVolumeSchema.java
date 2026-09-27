package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * One row per completed market trade. Kept as a log rather than a running
 * counter so that "volume over the last N hours" is an exact count over a time
 * window, and so the same table can answer both 24h and 7-day figures without
 * a rewrite step.
 */
public class TradeVolumeSchema extends Schema {

    public TradeVolumeSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "item_key " + t.varchar(128) + ", " +
                "amount " + t.integer() + ", " +
                "is_buy " + t.integer() + ", " +
                "trade_at " + t.time() +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.TRADE_VOLUME;
    }
}

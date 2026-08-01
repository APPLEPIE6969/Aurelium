package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

public class PriceHistorySchema extends Schema {

    public PriceHistorySchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "item_key " + t.varchar(128) + ", " +
                "buy_price " + t.decimal() + ", " +
                "sell_price " + t.decimal() + ", " +
                "timestamp " + t.time() +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.PRICE_HISTORY;
    }
}

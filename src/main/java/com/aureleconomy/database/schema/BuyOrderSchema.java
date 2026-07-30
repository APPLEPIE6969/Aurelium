package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

public class BuyOrderSchema extends Schema {

    public BuyOrderSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "buyer_uuid " + t.uuid() + ", " +
                "material " + t.varchar(64) + ", " +
                "amount_requested " + t.integer() + ", " +
                "amount_filled " + t.integer() + " DEFAULT 0, " +
                "price_per_piece " + t.decimal() + ", " +
                "currency " + t.varchar(32) + ", " +
                "status " + t.varchar(16) + " DEFAULT 'ACTIVE'" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.BUY_ORDERS;
    }
}

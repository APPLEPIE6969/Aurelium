package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

public class AuctionSchema extends Schema {

    public AuctionSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "seller_uuid " + t.uuid() + ", " +
                "item_data " + t.text() + ", " +
                "price " + t.decimal() + ", " +
                "currency " + t.varchar(32) + ", " +
                "is_bin " + t.bool() + ", " +
                "expiration " + t.time() + ", " +
                "highest_bidder_uuid " + t.uuid() + ", " +
                "ended " + t.bool() + " DEFAULT 0, " +
                "collected " + t.bool() + " DEFAULT 0, " +
                "listing_fee " + t.decimal() + " DEFAULT 0.0, " +
                "start_time " + t.time() + ", " +
                "purchase_mode " + t.varchar(16) + " DEFAULT 'STACK'" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.AUCTIONS;
    }
}

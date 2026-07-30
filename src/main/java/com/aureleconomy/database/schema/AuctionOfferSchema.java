package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * Bids placed on auctions. Must be created after {@link AuctionSchema} — it references it.
 */
public class AuctionOfferSchema extends Schema {

    public AuctionOfferSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "auction_id " + t.integer() + ", " +
                "bidder_uuid " + t.uuid() + ", " +
                "amount " + t.decimal() + ", " +
                "currency " + t.varchar(32) + ", " +
                "status " + t.varchar(16) + " DEFAULT 'PENDING', " +
                "timestamp " + t.time() + ", " +
                "FOREIGN KEY(auction_id) REFERENCES " + Table.AUCTIONS + "(id)" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.AUCTION_OFFERS;
    }
}

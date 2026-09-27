package com.aureleconomy.database.schema;

public enum Table {
    DATABASE_INFO("database_info"),
    PLAYERS("players"),
    PLAYER_BALANCES("player_balances"),
    AUCTIONS("auctions"),
    AUCTION_OFFERS("auction_offers"),
    OFFLINE_EARNINGS("offline_earnings"),
    BUY_ORDERS("buy_orders"),
    PRICE_HISTORY("price_history"),
    TRADE_VOLUME("trade_volume"),
    CUSTOM_ITEMS("custom_items");

    private final String table;

    Table(String table) {
        this.table = table;
    }

    @Override
    public String toString() {
        return table;
    }
}

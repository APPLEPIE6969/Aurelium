package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * Items discovered from other plugins by the scanner. Note this table uses
 * {@link SQLTypes#identifier(int)} / {@link SQLTypes#realNumber()} / {@link SQLTypes#flag()}
 * rather than the varchar/decimal types used by the older tables — see SQLTypes for why.
 */
public class CustomItemSchema extends Schema {

    public CustomItemSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "canonical_id " + t.identifier(255) + " PRIMARY KEY, " +
                "source_plugin " + t.identifier(64) + " NOT NULL, " +
                "display_name " + t.identifier(256) + ", " +
                "item_data " + t.text() + " NOT NULL, " +
                "pdc_key " + t.identifier(255) + ", " +
                "model_data_key " + t.identifier(128) + ", " +
                "lore_hash " + t.identifier(64) + ", " +
                "plugin_native_id " + t.identifier(255) + ", " +
                "category " + t.identifier(64) + ", " +
                "buy_price " + t.realNumber() + " DEFAULT -1, " +
                "sell_price " + t.realNumber() + " DEFAULT -1, " +
                "enabled " + t.flag() + " DEFAULT 1, " +
                "discovery_methods " + t.identifier(256) + ", " +
                "first_discovered " + t.bigInt() + " NOT NULL, " +
                "last_seen " + t.bigInt() + " NOT NULL" +
                ")" + t.tableSuffix();
    }

    @Override
    public Table getTable() {
        return Table.CUSTOM_ITEMS;
    }
}

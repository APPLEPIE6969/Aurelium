package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * H9: one row per dashboard purchase this server has claimed.
 *
 * <p>The dedup used to be an in-memory map purged after ten minutes and empty
 * after every restart, so the same purchase ID could be charged twice. The
 * claim is written before the purchase is executed, which is what makes the
 * answer survive a restart. {@code status} moves CLAIMED to READY when the
 * outcome is known, then to CONFIRMED once the dashboard has acknowledged it;
 * a READY row left by a failed confirmation is retried at startup.
 */
public class ProcessedPurchaseSchema extends Schema {

    public ProcessedPurchaseSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "purchase_id " + t.varchar(64) + " PRIMARY KEY, " +
                "status " + t.varchar(16) + " NOT NULL, " +
                "success " + t.bool() + " NOT NULL, " +
                "new_balance " + t.decimal() + ", " +
                "spent " + t.varchar(64) + ", " +
                "processed_at " + t.time() + " NOT NULL" +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.PROCESSED_PURCHASES;
    }
}

package com.aureleconomy.database.schema;

import com.aureleconomy.database.types.SQLTypes;

/**
 * F1: one row per balance mutation - what moved, between whom, and what the
 * balance was afterwards.
 *
 * <p>Every money bug in the fixplan printed money, and until this table there was
 * no record of how much was printed or by whom, so the damage could not be
 * measured after the fact. The ledger is written from the money primitives
 * ({@code withdraw}, {@code deposit}, {@code transfer}) rather than from each
 * feature, so a new caller cannot move money without appearing here.
 *
 * <p>{@code amount} is signed from the actor's point of view: negative when the
 * actor paid, positive when the actor received. {@code balance_after} is the
 * actor's balance in {@code currency} once the mutation committed, or null when
 * the actor has no balance row (the console, or a player who has never joined).
 */
public class LedgerSchema extends Schema {

    public LedgerSchema(SQLTypes t) {
        super(t);
    }

    @Override
    public String create() {
        return "CREATE TABLE IF NOT EXISTS " + getTable() + " (" +
                "id " + t.id() + ", " +
                "ts " + t.time() + " NOT NULL, " +
                "actor_uuid " + t.uuid() + ", " +
                "counterparty_uuid " + t.uuid() + ", " +
                "type " + t.varchar(32) + " NOT NULL, " +
                "item_key " + t.varchar(128) + ", " +
                "amount " + t.decimal() + " NOT NULL, " +
                "currency " + t.varchar(32) + ", " +
                "balance_after " + t.decimal() + ", " +
                "ref_id " + t.varchar(64) + ", " +
                "note " + t.varchar(255) +
                ")" + t.tableSuffix() + ";";
    }

    @Override
    public Table getTable() {
        return Table.TRANSACTION_LEDGER;
    }
}

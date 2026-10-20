package com.aureleconomy.economy;

/**
 * What a ledger row records. Stored by name, so a new value can be added without a
 * schema change and history can be filtered by it.
 *
 * <p>The amount on the row is signed from the actor's point of view: negative when
 * the actor paid, positive when the actor received. The reason says which part of
 * the economy the movement belongs to.
 */
public enum LedgerReason {

    /** A credit with no more specific reason. */
    DEPOSIT,
    /** A debit with no more specific reason. */
    WITHDRAW,
    /** One side of a transfer between two players. */
    TRANSFER,
    /** One side of a /pay. */
    PAY,
    /** An absolute balance set by an administrator, recorded as the delta. */
    ADMIN_SET,
    /** An administrator adding money with /eco give. */
    ADMIN_GIVE,
    /** An administrator removing money with /eco take. */
    ADMIN_TAKE,
    /** The starting balance credited to a new account. */
    STARTING_BALANCE,
    /** An auction bid, charged when the bid is placed. */
    BID,
    /** A displaced bid returned to the bidder who lost the top spot. */
    BID_REFUND,
    /** A seller's share of an auction sale. */
    AUCTION_SALE,
    /** Sales tax withheld from an auction sale. */
    AUCTION_TAX,
    /** Buy-order escrow taken from the buyer when the order is created. */
    ORDER_ESCROW,
    /** A buyer's refund when an order is cancelled. */
    ORDER_REFUND,
    /** A seller's payment for filling a buy order. */
    ORDER_FILL,
    /** A market purchase. */
    MARKET_BUY,
    /** A market sale. */
    MARKET_SELL,
    /** Earnings paid to a player while they were offline. */
    OFFLINE_EARNING,
}

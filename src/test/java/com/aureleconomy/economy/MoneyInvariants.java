package com.aureleconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Rule 2 of the fixplan, expressed once.
 *
 * <p>Every place the server holds money on behalf of players is summed here:
 * {@code player_balances}, the unfilled remainder of every active buy order
 * (where a buy order's escrow lives) and {@code offline_earnings}.
 *
 * <p>{@link MoneyInvariantTest} exposes these through instance methods so its
 * subclasses can call them without passing a connection. They are static and
 * {@code Connection}-taking underneath so a test outside this package (the
 * database-level concurrency test, which needs its own pooled connections)
 * asserts the same invariant instead of keeping a copy that can drift.
 */
public final class MoneyInvariants {

    static final BigDecimal ZERO = new BigDecimal("0.00");

    private MoneyInvariants() {
    }

    /**
     * Everything the server holds on behalf of players, in every place it lives.
     *
     * @see MoneyInvariantTest
     */
    public static BigDecimal totalMoney(Connection connection) throws SQLException {
        return sum(connection, "SELECT COALESCE(SUM(balance), 0) FROM player_balances")
                .add(sum(connection, "SELECT COALESCE(SUM(amount), 0) FROM offline_earnings"))
                .add(orderEscrow(connection));
    }

    /** The unfilled remainder of every active buy order. */
    public static BigDecimal orderEscrow(Connection connection) throws SQLException {
        return sum(connection, "SELECT COALESCE(SUM(price_per_piece * (amount_requested - amount_filled)), 0) "
                + "FROM buy_orders WHERE status = 'ACTIVE'");
    }

    /** A query returning a single SUM, treating no rows as zero. */
    public static BigDecimal sum(Connection connection, String sql) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBigDecimal(1) : ZERO;
        }
    }

    /**
     * Rule 2: the sum is the same after the action as before, so value only moved
     * between accounts.
     */
    public static void assertMoneyUnchanged(Connection connection, String phase, BigDecimal before)
            throws SQLException {
        BigDecimal after = totalMoney(connection);
        assertEquals(0, before.stripTrailingZeros().compareTo(after.stripTrailingZeros()),
                "money appeared or vanished during " + phase
                        + " (before " + before + ", after " + after + ")");
    }

    /** Rule 2, for a test that deliberately mints: the sum must rise by exactly this. */
    public static void assertMoneyMintedBy(Connection connection, String phase, BigDecimal before,
            BigDecimal minted) throws SQLException {
        BigDecimal after = totalMoney(connection);
        assertEquals(0, before.add(minted).stripTrailingZeros().compareTo(after.stripTrailingZeros()),
                "the mint during " + phase + " was not " + minted
                        + " (before " + before + ", after " + after + ")");
    }

    /** Rule 2, for a test that deliberately burns: the sum must fall by exactly this. */
    public static void assertMoneyBurnedBy(Connection connection, String phase, BigDecimal before,
            BigDecimal burned) throws SQLException {
        BigDecimal after = totalMoney(connection);
        assertEquals(0, before.subtract(burned).stripTrailingZeros().compareTo(after.stripTrailingZeros()),
                "the burn during " + phase + " was not " + burned
                        + " (before " + before + ", after " + after + ")");
    }

    /**
     * Scale-insensitive amount comparison. SQLite does not preserve a decimal
     * column's scale, so {@code 60.00} reads back as {@code 60.0} and
     * {@code equals} would fail on the formatting rather than the value.
     */
    public static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        if (actual == null) {
            assertEquals(null, expected, what + ": expected " + expected + " but found no row");
            return;
        }
        assertEquals(0, expected.stripTrailingZeros().compareTo(actual.stripTrailingZeros()),
                what + ": expected " + expected + " but was " + actual);
    }
}

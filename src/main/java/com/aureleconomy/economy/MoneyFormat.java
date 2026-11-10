package com.aureleconomy.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one rounding policy for money (L5).
 *
 * <p>The economy layer rounded balances with {@link RoundingMode#HALF_EVEN} while
 * auction prices, fees, taxes and web purchases rounded with
 * {@link RoundingMode#HALF_UP}. A price of 0.005 was therefore worth 0.01 when
 * it was computed and 0.00 when it was stored, and which one a player saw
 * depended on the path the number took. Prices are now HALF_UP everywhere, the
 * policy lives here, and money code calls {@link #normalize} instead of spelling
 * out a scale and a mode.
 *
 * <p>HALF_UP is away from zero in {@link RoundingMode}'s terms: 0.005 becomes
 * 0.01 and -0.005 becomes -0.01. Money is never negative in this plugin, so the
 * negative case is only pinned by the test.
 */
public final class MoneyFormat {

    /** Decimals every stored amount and every displayed price has. */
    public static final int SCALE = 2;

    /** The single rounding mode for money. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MoneyFormat() {
    }

    /**
     * Rounds {@code amount} to the stored scale. Use this before writing money
     * anywhere, and before comparing two amounts that came from different paths.
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, ROUNDING);
    }

    /** The stored value as plain text with {@link #SCALE} decimals and no grouping. */
    public static String plain(BigDecimal amount) {
        return normalize(amount).toPlainString();
    }
}

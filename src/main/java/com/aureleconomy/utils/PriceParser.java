package com.aureleconomy.utils;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import com.aureleconomy.AurelEconomy;

/**
 * H5: the one way a price typed by a player becomes a number.
 *
 * <p>Every chat prompt and command used to hand the raw text straight to
 * {@code new BigDecimal(...)}. That constructor accepts exponent notation and
 * stores the exponent as a scale, so {@code 1e999999999} looks cheap until the
 * first {@code setScale(2)} in the economy layer expands it into a billion digits
 * on the main thread. One chat message could stall or kill the server, and
 * nothing capped the value afterwards either, so a listing could be priced at
 * anything the parse allowed.
 *
 * <p>Input is matched against a plain-decimal shape before anything is
 * constructed, so exponent notation never reaches the parser at all. The value is
 * then bounded twice: at most {@value #HARD_DIGIT_LIMIT} integer digits and two
 * decimals by the pattern, and {@code economy.max-transaction-price} as the real
 * ceiling.
 */
public final class PriceParser {

    /**
     * Integer digits the pattern accepts. Eighteen digits still fits in a long, so
     * a ceiling raised to that range remains usable. The config decides the actual
     * limit; this only stops a big number from being built at all.
     */
    private static final int HARD_DIGIT_LIMIT = 18;

    /** Ceiling used when {@code economy.max-transaction-price} is missing. */
    public static final BigDecimal DEFAULT_MAXIMUM = new BigDecimal("1000000000000");

    /** Plain decimal only: no exponent, no grouping separators, no whitespace. */
    private static final Pattern SHAPE = Pattern.compile("-?[0-9]+(\\.[0-9]+)?");
    private static final Pattern ACCEPTABLE = Pattern.compile("-?[0-9]{1," + HARD_DIGIT_LIMIT + "}(\\.[0-9]{1,2})?");

    /** Why a piece of text was not accepted, with a sentence a player can read. */
    public enum Rejection {
        EMPTY("Enter an amount."),
        NOT_A_NUMBER("That is not a number."),
        TOO_MANY_DECIMALS("Use at most two decimal places."),
        TOO_LARGE("That amount is too large."),
        NEGATIVE("That amount cannot be negative.");

        private final String message;

        Rejection(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** Either an amount, or the reason there is none. Never both, never neither. */
    public record Parsed(BigDecimal amount, Rejection rejection) {
        public boolean ok() {
            return rejection == null;
        }
    }

    private PriceParser() {
    }

    /** Parses player input against the server's configured ceiling. */
    public static Parsed parse(String raw, AurelEconomy plugin) {
        return parse(raw, configuredMaximum(plugin));
    }

    public static Parsed parse(String raw, BigDecimal maximum) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return new Parsed(null, Rejection.EMPTY);
        }

        // Shape first, and without constructing anything: this is what turns
        // 1e999999999 into a rejected string instead of a billion-digit number.
        if (!SHAPE.matcher(text).matches()) {
            return new Parsed(null, Rejection.NOT_A_NUMBER);
        }

        if (!ACCEPTABLE.matcher(text).matches()) {
            int decimalPoint = text.indexOf('.');
            if (decimalPoint >= 0 && text.length() - decimalPoint - 1 > 2) {
                return new Parsed(null, Rejection.TOO_MANY_DECIMALS);
            }
            return new Parsed(null, Rejection.TOO_LARGE);
        }

        BigDecimal amount = new BigDecimal(text);
        if (amount.signum() < 0) {
            return new Parsed(null, Rejection.NEGATIVE);
        }
        if (maximum != null && amount.compareTo(maximum) > 0) {
            return new Parsed(null, Rejection.TOO_LARGE);
        }
        return new Parsed(amount, null);
    }

    /** The configured ceiling: {@code economy.max-transaction-price}. */
    public static BigDecimal configuredMaximum(AurelEconomy plugin) {
        return BigDecimal.valueOf(plugin.getConfig().getDouble("economy.max-transaction-price",
                DEFAULT_MAXIMUM.doubleValue()));
    }
}

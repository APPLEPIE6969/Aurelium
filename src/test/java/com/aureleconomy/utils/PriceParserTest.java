package com.aureleconomy.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aureleconomy.AurelEconomy;

/**
 * H5: a price typed by a player is refused before it can become a number.
 *
 * <p>{@code new BigDecimal("1e999999999")} keeps the exponent as a scale, and the
 * first {@code setScale(2)} in the economy layer expands that into a billion
 * digits. Every chat prompt and command used to hand the raw text to that
 * constructor, so a single message could stall the main thread. The parser now
 * matches a plain-decimal shape first, which means exponent notation is rejected
 * as a string and never reaches the constructor.
 */
class PriceParserTest {

    private static final BigDecimal MAXIMUM = PriceParser.DEFAULT_MAXIMUM;

    @Test
    @DisplayName("Exponent notation is rejected instead of being built")
    void exponentNotationIsRejected() {
        // A tiny exponent is the dangerous direction: 5e-999999999 is below any
        // ceiling, so the value would pass every later check and only blow up when
        // the economy layer scales it to two decimals.
        for (String text : new String[] { "1e999999999", "5e-999999999", "1e12", "1E12", "1e-12", "-1e12", "1.5e3" }) {
            PriceParser.Parsed parsed = PriceParser.parse(text, MAXIMUM);
            assertFalse(parsed.ok(), text + " should have been rejected");
            assertEquals(PriceParser.Rejection.NOT_A_NUMBER, parsed.rejection(), text);
            assertNull(parsed.amount(), text);
        }
    }

    @Test
    @DisplayName("A million digits is refused without being constructed")
    void aMillionDigitsIsRefused() {
        String digits = "1".repeat(1_000_000);
        long started = System.nanoTime();
        PriceParser.Parsed parsed = PriceParser.parse(digits, MAXIMUM);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(PriceParser.Rejection.TOO_LARGE, parsed.rejection());
        assertNull(parsed.amount());
        // Coarse smoke check only: the guarantee under test is the rejection above,
        // and building a million-digit BigDecimal would take far longer than this.
        assertTrue(millis < 2000, "rejecting a million digits took " + millis + "ms");
    }

    @Test
    @DisplayName("Signs, separators, unicode digits and junk are not numbers")
    void notNumbers() {
        for (String text : new String[] { "abc", "5,000", "0x10", "1.2.3", "+5", "NaN", "Infinity", "\u0661\u0662\u0663" }) {
            assertEquals(PriceParser.Rejection.NOT_A_NUMBER, PriceParser.parse(text, MAXIMUM).rejection(), text);
        }
    }

    @Test
    @DisplayName("Empty input is named as empty, not as a bad number")
    void emptyInput() {
        assertEquals(PriceParser.Rejection.EMPTY, PriceParser.parse("", MAXIMUM).rejection());
        assertEquals(PriceParser.Rejection.EMPTY, PriceParser.parse("   ", MAXIMUM).rejection());
        assertEquals(PriceParser.Rejection.EMPTY, PriceParser.parse(null, MAXIMUM).rejection());
    }

    @Test
    @DisplayName("Negative and over-precise amounts are rejected with their own reason")
    void negativeAndOverPrecise() {
        assertEquals(PriceParser.Rejection.NEGATIVE, PriceParser.parse("-5", MAXIMUM).rejection());
        assertEquals(PriceParser.Rejection.NEGATIVE, PriceParser.parse("-0.01", MAXIMUM).rejection());
        assertEquals(PriceParser.Rejection.TOO_MANY_DECIMALS, PriceParser.parse("0.005", MAXIMUM).rejection());
        assertEquals(PriceParser.Rejection.TOO_MANY_DECIMALS, PriceParser.parse("1.234", MAXIMUM).rejection());
    }

    @Test
    @DisplayName("Amounts above the ceiling are refused, at or below it are accepted")
    void ceiling() {
        assertEquals(PriceParser.Rejection.TOO_LARGE, PriceParser.parse("99999999999999", MAXIMUM).rejection());
        assertTrue(PriceParser.parse("1000000000000", MAXIMUM).ok());

        assertEquals(PriceParser.Rejection.TOO_LARGE, PriceParser.parse("5", new BigDecimal("4")).rejection());
        assertTrue(PriceParser.parse("4", new BigDecimal("4")).ok());
    }

    @Test
    @DisplayName("Plain amounts parse and are trimmed")
    void plainAmounts() {
        assertEquals(new BigDecimal("5"), PriceParser.parse("5", MAXIMUM).amount());
        assertEquals(new BigDecimal("5.50"), PriceParser.parse(" 5.50 ", MAXIMUM).amount());
        assertEquals(new BigDecimal("0.01"), PriceParser.parse("0.01", MAXIMUM).amount());
        assertEquals(BigDecimal.ZERO, PriceParser.parse("0", MAXIMUM).amount());
        assertEquals(new BigDecimal("999999999999"), PriceParser.parse("999999999999", MAXIMUM).amount());
        assertEquals(new BigDecimal("7"), PriceParser.parse("000007", MAXIMUM).amount());
    }

    @Test
    @DisplayName("The ceiling comes from economy.max-transaction-price")
    void configuredCeiling() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("economy.max-transaction-price", 1000.0d);
        AurelEconomy plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);

        assertEquals(new BigDecimal("1000.0"), PriceParser.configuredMaximum(plugin));
        assertTrue(PriceParser.parse("1000", plugin).ok());
        assertEquals(PriceParser.Rejection.TOO_LARGE, PriceParser.parse("1000.01", plugin).rejection());
    }

    @Test
    @DisplayName("A config without the key falls back to the built-in ceiling")
    void defaultCeiling() {
        AurelEconomy plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());

        assertEquals(0, PriceParser.DEFAULT_MAXIMUM.compareTo(PriceParser.configuredMaximum(plugin)));
        assertTrue(PriceParser.parse("1000000000000", plugin).ok());
        assertEquals(PriceParser.Rejection.TOO_LARGE, PriceParser.parse("1000000000001", plugin).rejection());
    }
}

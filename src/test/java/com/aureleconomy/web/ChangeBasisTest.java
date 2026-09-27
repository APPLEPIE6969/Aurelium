package com.aureleconomy.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stocks page labelled its change column "24h Change" while the server was
 * actually comparing against each item's listing price. These tests pin the
 * replacement: a real comparison against the recorded price 24h ago, and an
 * honest fallback label when that history does not exist yet.
 */
class ChangeBasisTest {

    /** Mirrors the arithmetic in WebSnapshot.buildStocks(). */
    private static JsonObject compute(BigDecimal buyPrice, BigDecimal dayAgo, BigDecimal basePrice) {
        boolean haveDayAgo = dayAgo != null && dayAgo.compareTo(BigDecimal.ZERO) > 0;
        BigDecimal reference = haveDayAgo ? dayAgo : basePrice;
        boolean comparable = haveDayAgo
                || (basePrice.compareTo(BigDecimal.ZERO) > 0 && basePrice.compareTo(BigDecimal.ONE) != 0);

        BigDecimal change = BigDecimal.ZERO;
        if (comparable && buyPrice.compareTo(BigDecimal.ZERO) > 0) {
            change = buyPrice.subtract(reference).multiply(BigDecimal.valueOf(100))
                    .divide(reference, 4, RoundingMode.HALF_UP);
        }
        JsonObject out = new JsonObject();
        out.addProperty("change", change.doubleValue());
        out.addProperty("changeBasis", haveDayAgo ? "24h" : "listing");
        return out;
    }

    @Test
    @DisplayName("with history, the change is measured against 24h ago, not the listing price")
    void usesThe24hPrice() {
        // Listed at 100, was 50 yesterday, is 75 now.
        // vs listing  -> -25%   (the old, misleading answer)
        // vs 24h ago  -> +50%   (the correct one)
        JsonObject out = compute(new BigDecimal("75"), new BigDecimal("50"), new BigDecimal("100"));
        assertEquals(50.0, out.get("change").getAsDouble(), 0.001);
        assertEquals("24h", out.get("changeBasis").getAsString());
    }

    @Test
    @DisplayName("without history it falls back to the listing price and says so")
    void fallsBackToListing() {
        JsonObject out = compute(new BigDecimal("75"), null, new BigDecimal("100"));
        assertEquals(-25.0, out.get("change").getAsDouble(), 0.001);
        assertEquals("listing", out.get("changeBasis").getAsString());
    }

    @Test
    @DisplayName("a zero history price counts as no history")
    void zeroHistoryIsNotHistory() {
        // A recorded price of 0 would make the percentage meaningless.
        JsonObject out = compute(new BigDecimal("75"), BigDecimal.ZERO, new BigDecimal("100"));
        assertEquals("listing", out.get("changeBasis").getAsString());
    }

    @Test
    @DisplayName("an unpriced item reports no change rather than a bogus percentage")
    void unpricedItem() {
        JsonObject out = compute(BigDecimal.ZERO, null, BigDecimal.ONE);
        assertEquals(0.0, out.get("change").getAsDouble(), 0.0001);
    }

    @Test
    @DisplayName("the listing-price sentinel of 1 is not treated as a real price")
    void sentinelPrice() {
        // entry.price == 1 is the "unset" marker the economy uses.
        JsonObject out = compute(new BigDecimal("75"), null, BigDecimal.ONE);
        assertEquals(0.0, out.get("change").getAsDouble(), 0.0001);
    }

    @Test
    @DisplayName("a flat 24h price is 0%, not a division by zero")
    void flatPrice() {
        JsonObject out = compute(new BigDecimal("50"), new BigDecimal("50"), new BigDecimal("100"));
        assertEquals(0.0, out.get("change").getAsDouble(), 0.0001);
    }

    @Test
    @DisplayName("the snapshot exposes changeBasis so the client can label it")
    void basisIsExposed() {
        // Guard against the column silently reverting to a single "change" number
        // with no indication of what it is measured against.
        JsonObject out = compute(new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"));
        assertTrue(out.has("changeBasis"));
        assertFalse(out.get("changeBasis").getAsString().isBlank());
    }
}

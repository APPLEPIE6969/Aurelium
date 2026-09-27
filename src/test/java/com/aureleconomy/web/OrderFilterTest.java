package com.aureleconomy.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The orders fill-state filter. This is the one thing a fulfiller filters by, so
 * the boundaries matter: an order with nothing filled and an order that is fully
 * filled must never both appear under "part filled".
 */
class OrderFilterTest {

    private static final String DATA = """
            [
              {"id":1,"itemName":"coal","amountRequested":64,"amountFilled":0},
              {"id":2,"itemName":"iron ingot","amountRequested":32,"amountFilled":8},
              {"id":3,"itemName":"diamond","amountRequested":16,"amountFilled":16},
              {"id":4,"itemName":"gold","amountRequested":10,"amountFilled":3}
            ]
            """;

    private static JsonArray rows() {
        return JsonParser.parseString(DATA).getAsJsonArray();
    }

    private static List<Integer> ids(JsonArray filtered) {
        return filtered.asList().stream()
                .map(e -> e.getAsJsonObject().get("id").getAsInt())
                .toList();
    }

    private static List<Integer> filter(String name) {
        return ids(DashboardApiHandler.filterOrders(rows(), name));
    }

    @Test
    @DisplayName("all returns everything")
    void all() {
        assertEquals(List.of(1, 2, 3, 4), filter("all"));
    }

    @Test
    @DisplayName("unfilled is only the orders with nothing delivered yet")
    void unfilled() {
        assertEquals(List.of(1), filter("unfilled"));
    }

    @Test
    @DisplayName("partial is strictly between the two extremes")
    void partial() {
        // id 2 (8/32) and id 4 (3/10). Not id 1 (0/64) and not id 3 (16/16).
        assertEquals(List.of(2, 4), filter("partial"));
    }

    @Test
    @DisplayName("filled is only the completely delivered orders")
    void filled() {
        assertEquals(List.of(3), filter("filled"));
    }

    @Test
    @DisplayName("the three buckets partition the set with no overlap")
    void bucketsAreDisjointAndComplete() {
        List<Integer> seen = new java.util.ArrayList<>();
        seen.addAll(filter("unfilled"));
        seen.addAll(filter("partial"));
        seen.addAll(filter("filled"));
        assertEquals(4, seen.size(), "every order lands in exactly one bucket");
        assertEquals(4, seen.stream().distinct().count(), "no order appears twice");
    }

    @Test
    @DisplayName("a blank, null or unknown filter shows everything rather than nothing")
    void unknownFilterIsSafe() {
        // A typo in a query param must not silently empty the page.
        assertEquals(4, filter(null).size());
        assertEquals(4, filter("").size());
        assertEquals(4, filter("   ").size());
        assertEquals(4, filter("nonsense").size());
    }

    @Test
    @DisplayName("filter names are case-insensitive")
    void caseInsensitive() {
        assertEquals(List.of(1), filter("UNFILLED"));
        assertEquals(List.of(3), filter("Filled"));
    }

    @Test
    @DisplayName("a zero-quantity order counts as unfilled")
    void zeroQuantity() {
        JsonArray odd = JsonParser.parseString("""
                [{"id":9,"itemName":"ghost","amountRequested":0,"amountFilled":0}]
                """).getAsJsonArray();
        assertEquals(1, DashboardApiHandler.filterOrders(odd, "unfilled").size());
        // 0/0 must not be reported as "fully filled".
        assertEquals(0, DashboardApiHandler.filterOrders(odd, "filled").size());
    }

    @Test
    @DisplayName("rows missing the fill fields are not counted as filled")
    void missingFields() {
        JsonArray odd = JsonParser.parseString("""
                [{"id":8,"itemName":"unknown"}]
                """).getAsJsonArray();
        JsonObject o = odd.get(0).getAsJsonObject();
        assertEquals(0, o.get("amountFilled") == null ? 0 : 1, "sanity: field really is absent");
        // Treated as 0 filled of 0 wanted -> unfilled, not complete.
        assertEquals(1, DashboardApiHandler.filterOrders(odd, "unfilled").size());
        assertEquals(0, DashboardApiHandler.filterOrders(odd, "filled").size());
    }

    @Test
    @DisplayName("non-object entries are skipped rather than throwing")
    void toleratesOddRows() {
        JsonArray mixed = new JsonArray();
        mixed.add(JsonParser.parseString("{\"id\":1,\"amountRequested\":4,\"amountFilled\":0}"));
        mixed.add(JsonParser.parseString("\"not an object\""));
        for (JsonElement el : mixed) {
            // Just assert the filter completes.
            DashboardApiHandler.filterOrders(mixed, "unfilled");
        }
        assertEquals(1, DashboardApiHandler.filterOrders(mixed, "unfilled").size());
    }
}

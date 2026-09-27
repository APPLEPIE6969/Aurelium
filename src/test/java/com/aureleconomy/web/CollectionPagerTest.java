package com.aureleconomy.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard drives one paginator against auctions, orders and stocks, so the
 * envelope, the search and the sort order all have to be right here.
 */
class CollectionPagerTest {

    private static final CollectionPager.Spec STOCKS = new CollectionPager.Spec(
            List.of("name", "key"),
            List.of("name", "buyPrice", "sellPrice", "volume", "trades", "change"),
            List.of("buyPrice", "sellPrice", "volume", "trades", "change"));

    private static JsonArray rows(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static JsonObject page(String json, int pageNo, int size, String q, String sort, boolean asc) {
        return CollectionPager.page(rows(json), pageNo, size, 200, q, sort, asc, STOCKS);
    }

    private static List<String> names(JsonObject out) {
        return out.getAsJsonArray("items").asList().stream()
                .map(e -> e.getAsJsonObject().get("name").getAsString())
                .toList();
    }

    private static final String DATA = """
            [
              {"name":"coal","key":"COAL","buyPrice":50,"sellPrice":45,"volume":0,"trades":0,"change":0},
              {"name":"diamond","key":"DIAMOND","buyPrice":100,"sellPrice":90,"volume":5,"trades":1,"change":2},
              {"name":"iron ingot","key":"IRON_INGOT","buyPrice":300,"sellPrice":280,"volume":900,"trades":40,"change":-3}
            ]
            """;

    @Test
    @DisplayName("envelope always reports page, totalPages, totalItems, pageSize and items")
    void envelope() {
        JsonObject out = page(DATA, 0, 28, null, null, true);
        assertEquals(0, out.get("page").getAsInt());
        assertEquals(1, out.get("totalPages").getAsInt());
        assertEquals(3, out.get("totalItems").getAsInt());
        assertEquals(28, out.get("pageSize").getAsInt());
        assertEquals(3, out.getAsJsonArray("items").size());
    }

    @Test
    @DisplayName("slices the requested window and reports the real page count")
    void slices() {
        JsonObject first = page(DATA, 0, 2, null, null, true);
        assertEquals(2, first.getAsJsonArray("items").size());
        assertEquals(2, first.get("totalPages").getAsInt());
        assertEquals(List.of("coal", "diamond"), names(first));

        JsonObject second = page(DATA, 1, 2, null, null, true);
        assertEquals(List.of("iron ingot"), names(second));
        assertEquals(1, second.get("page").getAsInt());
    }

    @Test
    @DisplayName("a partial last page is not padded")
    void partialLastPage() {
        JsonObject out = page(DATA, 1, 2, null, null, true);
        assertEquals(1, out.getAsJsonArray("items").size());
    }

    @Test
    @DisplayName("paging past the end clamps to the last page instead of returning nothing")
    void clampsPastEnd() {
        // Matters when a search shrinks the set while the browser is on page 5.
        JsonObject out = page(DATA, 99, 2, null, null, true);
        assertEquals(1, out.get("page").getAsInt());
        assertEquals(1, out.getAsJsonArray("items").size());
    }

    @Test
    @DisplayName("page size is clamped into a sane range")
    void clampsPageSize() {
        assertEquals(1, page(DATA, 0, 0, null, null, true).get("pageSize").getAsInt());
        assertEquals(1, page(DATA, 0, -5, null, null, true).get("pageSize").getAsInt());
        // Above the cap the request is clamped rather than trusted.
        assertEquals(200, page(DATA, 0, 100000, null, null, true).get("pageSize").getAsInt());
    }

    @Test
    @DisplayName("negative page numbers clamp to the first page")
    void clampsNegative() {
        assertEquals(0, page(DATA, -3, 2, null, null, true).get("page").getAsInt());
    }

    @Test
    @DisplayName("search is case-insensitive across the searchable fields")
    void search() {
        assertEquals(List.of("iron ingot"), names(page(DATA, 0, 28, "IRON", null, true)));
        assertEquals(List.of("diamond"), names(page(DATA, 0, 28, "diam", null, true)));
        assertEquals(0, page(DATA, 0, 28, "nothingmatches", null, true).get("totalItems").getAsInt());
    }

    @Test
    @DisplayName("blank search is ignored rather than matching nothing")
    void blankSearch() {
        assertEquals(3, page(DATA, 0, 28, "   ", null, true).get("totalItems").getAsInt());
        assertEquals(3, page(DATA, 0, 28, null, null, true).get("totalItems").getAsInt());
    }

    @Test
    @DisplayName("text columns sort alphabetically, numeric columns by magnitude")
    void sorting() {
        assertEquals(List.of("coal", "diamond", "iron ingot"),
                names(page(DATA, 0, 28, null, "name", true)));
        assertEquals(List.of("iron ingot", "diamond", "coal"),
                names(page(DATA, 0, 28, null, "name", false)));

        assertEquals(List.of("iron ingot", "diamond", "coal"),
                names(page(DATA, 0, 28, null, "buyPrice", false)));
        assertEquals(List.of("coal", "diamond", "iron ingot"),
                names(page(DATA, 0, 28, null, "buyPrice", true)));
    }

    @Test
    @DisplayName("sorting is stable enough that equal keys keep a deterministic order")
    void deterministicTies() {
        String ties = """
                [
                  {"name":"a","key":"A","buyPrice":10,"sellPrice":1,"volume":0,"trades":0,"change":0},
                  {"name":"b","key":"B","buyPrice":10,"sellPrice":1,"volume":0,"trades":0,"change":0},
                  {"name":"c","key":"C","buyPrice":10,"sellPrice":1,"volume":0,"trades":0,"change":0}
                ]
                """;
        assertEquals(List.of("a", "b", "c"), names(page(ties, 0, 28, null, "buyPrice", false)));
    }

    @Test
    @DisplayName("a sort field the spec does not allow is ignored instead of erroring")
    void rejectsUnknownSort() {
        JsonObject out = page(DATA, 0, 28, null, "currencySymbol", false);
        assertEquals(3, out.get("totalItems").getAsInt());
        assertEquals(3, out.getAsJsonArray("items").size());
    }

    @Test
    @DisplayName("search and sort compose, and the total reflects the filtered set")
    void searchThenSort() {
        JsonObject out = page(DATA, 0, 28, "i", "buyPrice", false);
        assertEquals(2, out.get("totalItems").getAsInt());
        assertEquals(List.of("iron ingot", "diamond"), names(out));
    }

    @Test
    @DisplayName("missing fields are treated as zero / empty instead of throwing")
    void toleratesMissingFields() {
        String sparse = """
                [
                  {"name":"a","key":"A"},
                  {"name":"b","key":"B","buyPrice":5}
                ]
                """;
        assertEquals(2, page(sparse, 0, 28, null, "volume", false).get("totalItems").getAsInt());
        assertEquals(1, page(sparse, 0, 28, "B", null, true).get("totalItems").getAsInt());
    }

    @Test
    @DisplayName("an empty collection still returns a usable envelope")
    void emptyCollection() {
        JsonObject out = page("[]", 0, 28, null, "name", true);
        assertEquals(0, out.get("totalItems").getAsInt());
        assertEquals(1, out.get("totalPages").getAsInt());
        assertEquals(0, out.getAsJsonArray("items").size());
        assertTrue(out.has("page"));
    }

    @Test
    @DisplayName("a null collection is handled like an empty one")
    void nullCollection() {
        JsonObject out = CollectionPager.page(null, 0, 28, 200, null, null, true, STOCKS);
        assertEquals(0, out.get("totalItems").getAsInt());
    }
}

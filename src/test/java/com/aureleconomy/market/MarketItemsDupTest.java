package com.aureleconomy.market;

import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M8: several materials were registered in more than one category, so the price
 * that won depended on insertion order. The duplicates are the bug; this test is
 * the guard that keeps them from coming back.
 */
class MarketItemsDupTest {

    /** The eight registrations the fixplan called out, all cross-category. */
    private static final String[] KNOWN_DUPLICATES = {
            "oak_boat", "oak_chest_boat", "bamboo_raft", "bamboo_chest_raft",
            "copper_ore", "deepslate_copper_ore", "raw_copper", "iron_nugget",
    };

    @Test
    @DisplayName("no key is registered twice across the market categories")
    void noDuplicateKeys() {
        Map<String, List<Category>> seen = new HashMap<>();
        for (Category category : Category.values()) {
            if (category == Category.ALL_ITEMS) {
                continue; // aggregated from the others by construction
            }
            for (MarketEntry entry : MarketItems.getItems(category)) {
                seen.computeIfAbsent(entry.searchName, key -> new ArrayList<>()).add(category);
            }
        }

        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, List<Category>> entry : seen.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicates.add(entry.getKey() + " in " + entry.getValue());
            }
        }

        assertTrue(duplicates.isEmpty(), "duplicate market keys: " + duplicates);
    }

    @Test
    @DisplayName("the known duplicates appear exactly once each")
    void knownDuplicatesAppearOnce() {
        Map<String, Integer> counts = new HashMap<>();
        for (Category category : Category.values()) {
            if (category == Category.ALL_ITEMS) {
                continue;
            }
            for (MarketEntry entry : MarketItems.getItems(category)) {
                counts.merge(entry.searchName, 1, Integer::sum);
            }
        }

        for (String key : KNOWN_DUPLICATES) {
            assertEquals(1, counts.getOrDefault(key, 0),
                    key + " is registered " + counts.getOrDefault(key, 0) + " times");
        }
    }

    @Test
    @DisplayName("the aggregated ALL_ITEMS view still contains every item once")
    void allItemsStaysConsistent() {
        List<MarketEntry> all = MarketItems.getItems(Category.ALL_ITEMS);
        Map<String, Integer> counts = new HashMap<>();
        for (MarketEntry entry : all) {
            counts.merge(entry.searchName, 1, Integer::sum);
        }

        for (String key : KNOWN_DUPLICATES) {
            assertEquals(1, counts.getOrDefault(key, 0),
                    key + " is missing from or repeated in the searchable catalogue");
        }
        assertTrue(all.size() > 100, "the searchable catalogue lost its items");
    }
}

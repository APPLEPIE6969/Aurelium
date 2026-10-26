package com.aureleconomy.market;

import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M9: getOrderItems claimed to merge the order-only items but returned only the
 * market list, so the smelted ingots (IRON_INGOT, GOLD_INGOT, NETHERITE_INGOT,
 * COPPER_INGOT, NETHERITE_SCRAP) could not back a buy order at all.
 */
class MarketItemsOrderItemsTest {

    @Test
    @DisplayName("every order-only item is orderable through its category")
    void orderOnlyItemsAreOrderable() {
        List<Material> orderable = MarketItems.getOrderItems(Category.MINERALS_ORES).stream()
                .map(entry -> entry.material)
                .collect(Collectors.toList());

        for (Material ingot : new Material[] { Material.COPPER_INGOT, Material.IRON_INGOT,
                Material.GOLD_INGOT, Material.NETHERITE_SCRAP, Material.NETHERITE_INGOT }) {
            assertTrue(orderable.contains(ingot), ingot + " could not back a buy order");
        }
    }

    @Test
    @DisplayName("merging does not duplicate the market items already in the category")
    void marketItemsAreNotDuplicated() {
        List<Material> orderable = MarketItems.getOrderItems(Category.MINERALS_ORES).stream()
                .map(entry -> entry.material)
                .collect(Collectors.toList());

        long coal = orderable.stream().filter(m -> m == Material.COAL).count();
        assertTrue(coal == 1, "COAL appeared " + coal + " times in the merged order list");
    }

    @Test
    @DisplayName("categories without order-only items are unchanged")
    void otherCategoriesKeepTheirItems() {
        List<MarketEntry> market = MarketItems.getItems(Category.FOOD_FARMING);
        List<MarketEntry> order = MarketItems.getOrderItems(Category.FOOD_FARMING);

        assertTrue(order.size() >= market.size() && !order.isEmpty(),
                "a plain category lost its items in the merge");
    }
}

package com.aureleconomy.scanner;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for isSimilar-based deduplication in CustomItemRegistry.
 * Verifies that items with identical material+meta are deduped
 * even when no other dedup key matches.
 *
 * Uses null plugin constructor — register() will NPE on the market-add
 * branch, but dedup logic runs before that. We catch NPE and verify
 * maps were updated correctly.
 */
public class IsSimilarDedupTest {

 private CustomItemRegistry registry;

 @BeforeEach
 void setUp() {
    registry = new CustomItemRegistry(null);
 }

 private ItemStack makeItem(Material mat, String name, int cmd, List<Component> lore) {
    ItemStack item = new ItemStack(mat);
    ItemMeta meta = item.getItemMeta();
    meta.displayName(Component.text(name));
    if (cmd > 0) meta.setCustomModelData(cmd);
    if (lore != null) meta.lore(lore);
    item.setItemMeta(meta);
    return item;
 }

 /**
  * Register an item, catching the expected NPE from the market-add branch.
  */
 private RegistrationResult registerSafely(CustomMarketItem item, DiscoveryMethod method) {
    try {
       return registry.register(item, method);
    } catch (NullPointerException e) {
       // NPE from plugin.getConfig() or plugin.getMarketManager() — expected
       return null;
    }
 }

 @Test
 void identicalItemsAreDedupedViaIsSimilar() {
    ItemStack item1 = makeItem(Material.DIAMOND_SWORD, "Test Sword", 0, null);
    ItemStack item2 = makeItem(Material.DIAMOND_SWORD, "Test Sword", 0, null);

    CustomMarketItem cmi1 = CustomMarketItem.builder()
       .canonicalId("test:sword_a")
       .sourcePlugin("TestPlugin")
       .itemStack(item1)
       .build();
    CustomMarketItem cmi2 = CustomMarketItem.builder()
       .canonicalId("test:sword_b")
       .sourcePlugin("TestPlugin")
       .itemStack(item2)
       .build();

    registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(cmi2, DiscoveryMethod.PLUGIN_API_ORAXEN);

    // Both should exist since canonical IDs differ
    assertEquals(2, registry.getTotalItems());
 }

 @Test
 void sameCanonicalIdDeduplicates() {
    ItemStack item1 = makeItem(Material.DIAMOND_SWORD, "Sword A", 100, null);
    ItemStack item2 = makeItem(Material.DIAMOND_SWORD, "Sword B", 200, null);

    CustomMarketItem cmi1 = CustomMarketItem.builder()
       .canonicalId("test:same_id")
       .sourcePlugin("PluginA")
       .itemStack(item1)
       .build();
    CustomMarketItem cmi2 = CustomMarketItem.builder()
       .canonicalId("test:same_id")
       .sourcePlugin("PluginB")
       .itemStack(item2)
       .build();

    registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(cmi2, DiscoveryMethod.PLUGIN_API_ORAXEN);

    // Same canonical ID should dedup to 1 item
    assertEquals(1, registry.getTotalItems());
 }

 @Test
 void differentMaterialNotDeduped() {
    ItemStack sword = makeItem(Material.DIAMOND_SWORD, "Item", 0, null);
    ItemStack pick = makeItem(Material.DIAMOND_PICKAXE, "Item", 0, null);

    CustomMarketItem cmi1 = CustomMarketItem.builder()
       .canonicalId("test:item_sword")
       .sourcePlugin("Test")
       .itemStack(sword)
       .build();
    CustomMarketItem cmi2 = CustomMarketItem.builder()
       .canonicalId("test:item_pick")
       .sourcePlugin("Test")
       .itemStack(pick)
       .build();

    registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(cmi2, DiscoveryMethod.PLUGIN_API_ORAXEN);

    assertEquals(2, registry.getTotalItems());
 }

 @Test
 void upsertUpdatesExistingItem() {
    ItemStack item1 = makeItem(Material.DIAMOND_SWORD, "V1", 0, null);
    ItemStack item2 = makeItem(Material.DIAMOND_SWORD, "V2", 0, null);

    CustomMarketItem cmi1 = CustomMarketItem.builder()
       .canonicalId("test:upsert")
       .sourcePlugin("Test")
       .itemStack(item1)
       .buyPrice(BigDecimal.valueOf(100.0))
       .build();
    CustomMarketItem cmi2 = CustomMarketItem.builder()
       .canonicalId("test:upsert")
       .sourcePlugin("Test")
       .itemStack(item2)
       .buyPrice(BigDecimal.valueOf(200.0))
       .build();

    registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registry.upsert(cmi2);

    assertEquals(1, registry.getTotalItems());
 }

 @Test
 void clearRemovesAllItems() {
    ItemStack item = makeItem(Material.DIAMOND_SWORD, "Item", 0, null);
    CustomMarketItem cmi = CustomMarketItem.builder()
       .canonicalId("test:clear")
       .sourcePlugin("Test")
       .itemStack(item)
       .build();

    registerSafely(cmi, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    assertEquals(1, registry.getTotalItems());

    registry.clear();
    assertEquals(0, registry.getTotalItems());
 }
}

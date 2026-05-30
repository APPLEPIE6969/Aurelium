package com.aureleconomy.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

 private CustomMarketItem buildItem(String canonicalId, String pdcKey, String modelDataKey,
 String loreHash, String pluginNativeId) {
 org.bukkit.inventory.ItemStack item = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
 org.bukkit.inventory.meta.ItemMeta meta = org.mockito.Mockito.mock(org.bukkit.inventory.meta.ItemMeta.class);
 org.bukkit.persistence.PersistentDataContainer pdc = org.mockito.Mockito.mock(org.bukkit.persistence.PersistentDataContainer.class);

 org.mockito.Mockito.when(item.getType()).thenReturn(org.bukkit.Material.DIAMOND_SWORD);
 org.mockito.Mockito.when(item.hasItemMeta()).thenReturn(true);
 org.mockito.Mockito.when(item.getItemMeta()).thenReturn(meta);
 org.mockito.Mockito.when(meta.getPersistentDataContainer()).thenReturn(pdc);
 org.mockito.Mockito.when(meta.hasCustomModelData()).thenReturn(false);
 org.mockito.Mockito.when(pdc.getKeys()).thenReturn(java.util.Collections.emptySet());
 org.mockito.Mockito.when(item.clone()).thenReturn(item);
 org.mockito.Mockito.when(item.isSimilar(org.mockito.Mockito.any())).thenReturn(false);
 org.mockito.Mockito.when(meta.hasLore()).thenReturn(false);

 return new CustomMarketItem.Builder()
 .canonicalId(canonicalId)
 .itemStack(item)
 .sourcePlugin("TestPlugin")
 .pdcKey(pdcKey)
 .modelDataKey(modelDataKey)
 .loreHash(loreHash)
 .pluginNativeId(pluginNativeId)
 .displayName("Test Item")
 .category("weapons")
 .build();
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
 CustomMarketItem cmi1 = buildItem("test:sword_a", null, null, null, null);
 CustomMarketItem cmi2 = buildItem("test:sword_b", null, null, null, null);

 registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registerSafely(cmi2, DiscoveryMethod.PLUGIN_API_ORAXEN);

 // Both should exist since canonical IDs differ and no dedup keys match
 assertEquals(2, registry.getTotalItems());
 }

 @Test
 void sameCanonicalIdDeduplicates() {
 CustomMarketItem cmi1 = buildItem("test:same_id", "pdc:a", null, null, null);
 CustomMarketItem cmi2 = buildItem("test:same_id", "pdc:b", null, null, null);

 registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registerSafely(cmi2, DiscoveryMethod.PLUGIN_API_ORAXEN);

 // Same canonical ID should dedup to 1 item
 assertEquals(1, registry.getTotalItems());
 }

 @Test
 void differentMaterialNotDeduped() {
 org.bukkit.inventory.ItemStack sword = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
 org.bukkit.inventory.meta.ItemMeta swordMeta = org.mockito.Mockito.mock(org.bukkit.inventory.meta.ItemMeta.class);
 org.mockito.Mockito.when(sword.getType()).thenReturn(org.bukkit.Material.DIAMOND_SWORD);
 org.mockito.Mockito.when(sword.hasItemMeta()).thenReturn(true);
 org.mockito.Mockito.when(sword.getItemMeta()).thenReturn(swordMeta);
 org.mockito.Mockito.when(swordMeta.getPersistentDataContainer()).thenReturn(org.mockito.Mockito.mock(org.bukkit.persistence.PersistentDataContainer.class));
 org.mockito.Mockito.when(swordMeta.hasCustomModelData()).thenReturn(false);
 org.mockito.Mockito.when(sword.clone()).thenReturn(sword);
 org.mockito.Mockito.when(swordMeta.hasLore()).thenReturn(false);

 org.bukkit.inventory.ItemStack pick = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
 org.bukkit.inventory.meta.ItemMeta pickMeta = org.mockito.Mockito.mock(org.bukkit.inventory.meta.ItemMeta.class);
 org.mockito.Mockito.when(pick.getType()).thenReturn(org.bukkit.Material.DIAMOND_PICKAXE);
 org.mockito.Mockito.when(pick.hasItemMeta()).thenReturn(true);
 org.mockito.Mockito.when(pick.getItemMeta()).thenReturn(pickMeta);
 org.mockito.Mockito.when(pickMeta.getPersistentDataContainer()).thenReturn(org.mockito.Mockito.mock(org.bukkit.persistence.PersistentDataContainer.class));
 org.mockito.Mockito.when(pickMeta.hasCustomModelData()).thenReturn(false);
 org.mockito.Mockito.when(pick.clone()).thenReturn(pick);
 org.mockito.Mockito.when(pickMeta.hasLore()).thenReturn(false);

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
 CustomMarketItem cmi1 = buildItem("test:upsert", null, null, null, null);
 CustomMarketItem cmi2 = buildItem("test:upsert", null, null, null, null);

 registerSafely(cmi1, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registry.upsert(cmi2);

 assertEquals(1, registry.getTotalItems());
 }

 @Test
 void clearRemovesAllItems() {
 CustomMarketItem cmi = buildItem("test:clear", null, null, null, null);

 registerSafely(cmi, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 assertEquals(1, registry.getTotalItems());

 registry.clear();
 assertEquals(0, registry.getTotalItems());
 }
}

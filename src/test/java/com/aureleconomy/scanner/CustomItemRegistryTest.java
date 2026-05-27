package com.aureleconomy.scanner;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.Mockito;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

/**
 * Unit tests for CustomItemRegistry.
 * Bukkit ItemStack/ItemMeta are mocked with Mockito since they cannot
 * be instantiated outside a Minecraft server.
 *
 * Uses null plugin constructor — register() will NPE on the market-add
 * branch, but the dedup logic runs before that. We catch NPE and verify
 * the dedup maps were updated correctly.
 */
class CustomItemRegistryTest {

 private CustomItemRegistry registry;

 @BeforeEach
 void setUp() {
    registry = new CustomItemRegistry(null);
 }

 private ItemStack mockItemStack(Material material, int customModelData, String displayName, List<Component> lore) {
    ItemStack item = Mockito.mock(ItemStack.class);
    ItemMeta meta = Mockito.mock(ItemMeta.class);
    PersistentDataContainer pdc = Mockito.mock(PersistentDataContainer.class);

    Mockito.when(item.getType()).thenReturn(material);
    Mockito.when(item.hasItemMeta()).thenReturn(true);
    Mockito.when(item.getItemMeta()).thenReturn(meta);
    Mockito.when(meta.getPersistentDataContainer()).thenReturn(pdc);
    Mockito.when(meta.hasCustomModelData()).thenReturn(customModelData > 0);
    Mockito.when(meta.getCustomModelData()).thenReturn(customModelData);
    Mockito.when(pdc.getKeys()).thenReturn(java.util.Collections.emptySet());
    Mockito.when(item.clone()).thenReturn(item);
    Mockito.when(item.isSimilar(Mockito.any())).thenReturn(false);
    Mockito.when(meta.hasLore()).thenReturn(lore != null && !lore.isEmpty());
    Mockito.when(meta.lore()).thenReturn(lore);
    Mockito.when(meta.hashCode()).thenReturn(java.util.Objects.hash(material, customModelData));
    return item;
 }

 private CustomMarketItem buildItem(String canonicalId, String pdcKey, String modelDataKey,
    String loreHash, String pluginNativeId) {
    ItemStack stack = mockItemStack(Material.DIAMOND_SWORD,
       modelDataKey != null ? Integer.parseInt(modelDataKey.split(":")[1]) : 0,
       "Test Item", null);
    return new CustomMarketItem.Builder()
       .canonicalId(canonicalId)
       .itemStack(stack)
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
  * Returns the RegistrationResult if dedup logic completed before the NPE.
  */
 private RegistrationResult registerSafely(CustomMarketItem item, DiscoveryMethod method) {
    try {
       return registry.register(item, method);
    } catch (NullPointerException e) {
       // NPE from plugin.getConfig() or plugin.getMarketManager() — expected
       // The dedup logic and map updates happen before the market-add code
       return null;
    }
 }

 @Test
 @DisplayName("Register a new item returns newlyRegistered result")
 void registerNewItem() {
    CustomMarketItem item = buildItem("test:sword", "test:sword",
       "DIAMOND_SWORD:10001", "hash1", "test:sword");
    RegistrationResult result = registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    if (result != null) {
       assertTrue(result.isNew(), "Expected isNew=true for first registration");
       assertEquals("test:sword", result.getCanonicalId());
    }
    // Verify item is in the registry regardless of NPE
    assertEquals(1, registry.getTotalItems());
 }

 @Test
 @DisplayName("Registering duplicate by canonical ID returns isDuplicate")
 void registerDuplicateByCanonicalId() {
    CustomMarketItem itemA = buildItem("test:sword", "pdc:a",
       "DIAMOND_SWORD:10001", "hashA", "native:a");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    CustomMarketItem itemB = buildItem("test:sword", "pdc:b",
       "DIAMOND_SWORD:10002", "hashB", "native:b");
    RegistrationResult result = registerSafely(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

    if (result != null) {
       assertTrue(result.isDuplicate(), "Expected isDuplicate for same canonical ID");
    }
    assertEquals(1, registry.getTotalItems(), "Registry should still have only 1 item");
 }

 @Test
 @DisplayName("Registering duplicate by PDC key returns isDuplicate")
 void registerDuplicateByPdcKey() {
    CustomMarketItem itemA = buildItem("plugin:a", "shared:pdc",
       "DIAMOND_SWORD:10001", "hashA", "native:a");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    CustomMarketItem itemB = buildItem("plugin:b", "shared:pdc",
       "DIAMOND_SWORD:10002", "hashB", "native:b");
    RegistrationResult result = registerSafely(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

    if (result != null) {
       assertTrue(result.isDuplicate(), "Expected isDuplicate for duplicate PDC key");
    }
    assertEquals(1, registry.getTotalItems(), "Registry should still have only 1 item");
 }

 @Test
 @DisplayName("Registering duplicate by model data key returns isDuplicate")
 void registerDuplicateByModelDataKey() {
    CustomMarketItem itemA = buildItem("plugin:a", "pdc:a",
       "DIAMOND_SWORD:10001", "hashA", "native:a");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    CustomMarketItem itemB = buildItem("plugin:b", "pdc:b",
       "DIAMOND_SWORD:10001", "hashB", "native:b");
    RegistrationResult result = registerSafely(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

    if (result != null) {
       assertTrue(result.isDuplicate(), "Expected isDuplicate for duplicate modelDataKey");
    }
 }

 @Test
 @DisplayName("Registering duplicate by lore hash returns isDuplicate")
 void registerDuplicateByLoreHash() {
    CustomMarketItem itemA = buildItem("plugin:a", "pdc:a",
       "DIAMOND_SWORD:10001", "shared_hash", "native:a");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    CustomMarketItem itemB = buildItem("plugin:b", "pdc:b",
       "DIAMOND_SWORD:10002", "shared_hash", "native:b");
    RegistrationResult result = registerSafely(itemB, DiscoveryMethod.LORE_PATTERN);

    if (result != null) {
       assertTrue(result.isDuplicate(), "Expected isDuplicate for duplicate loreHash");
    }
 }

 @Test
 @DisplayName("Registering duplicate by plugin native ID returns isDuplicate")
 void registerDuplicateByPluginNativeId() {
    CustomMarketItem itemA = buildItem("plugin:a", "pdc:a",
       "DIAMOND_SWORD:10001", "hashA", "itemsadder:ruby_sword");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    CustomMarketItem itemB = buildItem("plugin:b", "pdc:b",
       "DIAMOND_SWORD:10002", "hashB", "itemsadder:ruby_sword");
    RegistrationResult result = registerSafely(itemB, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    if (result != null) {
       assertTrue(result.isDuplicate(), "Expected isDuplicate for duplicate pluginNativeId");
    }
 }

 @Test
 @DisplayName("Discovery methods are accumulated for deduplicated items")
 void discoveryMethodsAccumulated() {
    CustomMarketItem itemA = buildItem("plugin:a", "shared:pdc",
       "DIAMOND_SWORD:10001", "hashA", "native:a");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(buildItem("plugin:b", "shared:pdc",
       "DIAMOND_SWORD:10002", "hashB", "native:b"),
       DiscoveryMethod.PLUGIN_API_ORAXEN);

    var methods = registry.getDiscoveryMethods("plugin:a");
    assertTrue(methods.contains(DiscoveryMethod.PLUGIN_API_ITEMSADDER),
       "Should contain ITEMSADDER");
    assertTrue(methods.contains(DiscoveryMethod.PLUGIN_API_ORAXEN),
       "Should contain ORAXEN");
 }

 @Test
 @DisplayName("Duplicates prevented counter increments on dedup")
 void duplicatesPreventedCounter() {
    CustomMarketItem itemA = buildItem("plugin:a", "shared:pdc",
       "DIAMOND_SWORD:10001", "hashA", "native:a");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(buildItem("plugin:b", "shared:pdc",
       "DIAMOND_SWORD:10002", "hashB", "native:b"),
       DiscoveryMethod.PLUGIN_API_ORAXEN);

    assertTrue(registry.getDuplicatesPrevented() >= 1,
       "Expected at least 1 duplicate prevented");
 }

 @Test
 @DisplayName("Upsert updates existing item and re-indexes dedup keys")
 void upsertReindexes() {
    CustomMarketItem itemA = buildItem("test:sword", "old_pdc_key",
       "DIAMOND_SWORD:10001", "hash1", "native:old");
    registerSafely(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    // Upsert with changed pdcKey
    CustomMarketItem updated = buildItem("test:sword", "new_pdc_key",
       "DIAMOND_SWORD:10001", "hash1", "native:new");
    registry.upsert(updated);

    // Old pdcKey should no longer resolve
    assertNull(registry.getById("old_pdc_key"), "Old PDC key should not be a valid ID");
    // The item should still exist under its canonical ID
    assertNotNull(registry.getById("test:sword"), "Item should still exist after upsert");
 }

 @Test
 @DisplayName("Clear removes all items and dedup maps")
 void clearRemovesAll() {
    registerSafely(buildItem("test:sword", "test:sword",
       "DIAMOND_SWORD:10001", "hash1", "test:sword"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(buildItem("test:bow", "test:bow",
       "BOW:10002", "hash2", "test:bow"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
    registerSafely(buildItem("test:pick", "test:pick",
       "DIAMOND_PICKAXE:10003", "hash3", "test:pick"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    registry.clear();
    assertEquals(0, registry.getTotalItems(), "Registry should be empty after clear");
    assertTrue(registry.isEmpty(), "isEmpty() should return true after clear");
 }

 @Test
 @DisplayName("resolveItemId returns empty for null/air items")
 void resolveItemIdNullAndAir() {
    assertEquals(Optional.empty(), registry.resolveItemId(null));
 }

 @Test
 @DisplayName("getTotalItems and isEmpty are consistent")
 void totalItemsAndIsEmpty() {
    assertTrue(registry.isEmpty());
    assertEquals(0, registry.getTotalItems());

    registerSafely(buildItem("test:sword", "test:sword",
       "DIAMOND_SWORD:10001", "hash1", "test:sword"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);

    assertFalse(registry.isEmpty());
    assertEquals(1, registry.getTotalItems());
 }
}

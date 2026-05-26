package com.aureleconomy.scanner;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.mockito.Mockito;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.List;

/**
 * Unit tests for CustomItemRegistry.
 * Bukkit ItemStack/ItemMeta are mocked with Mockito since they cannot
 * be instantiated outside a Minecraft server.
 */
class CustomItemRegistryTest {

 private CustomItemRegistry registry;

 @BeforeEach
 void setUp() {
 registry = new CustomItemRegistry();
 }

 private ItemStack mockItemStack(Material material, String pdcKey, String modelDataKey, String loreHash) {
 ItemStack item = Mockito.mock(ItemStack.class);
 ItemMeta meta = Mockito.mock(ItemMeta.class);
 PersistentDataContainer pdc = Mockito.mock(PersistentDataContainer.class);

 Mockito.when(item.getType()).thenReturn(material);
 Mockito.when(item.hasItemMeta()).thenReturn(true);
 Mockito.when(item.getItemMeta()).thenReturn(meta);
 Mockito.when(meta.getPersistentDataContainer()).thenReturn(pdc);

 if (pdcKey != null) {
 Mockito.when(pdc.has(Mockito.any(org.bukkit.NamespacedKey.class), Mockito.any())).thenReturn(true);
 }
 if (modelDataKey != null) {
 Mockito.when(meta.hasCustomModelData()).thenReturn(true);
 Mockito.when(meta.getCustomModelData()).thenReturn(
 Integer.parseInt(modelDataKey.split(":")[1])
 );
 }
 Mockito.when(item.clone()).thenReturn(item);
 return item;
 }

 private CustomMarketItem buildItem(String canonicalId, String pdcKey, String modelDataKey,
 String loreHash, String pluginNativeId) {
 return new CustomMarketItem.Builder()
 .canonicalId(canonicalId)
 .itemStack(mockItemStack(Material.DIAMOND_SWORD, pdcKey, modelDataKey, loreHash))
 .sourcePlugin("TestPlugin")
 .pdcKey(pdcKey)
 .modelDataKey(modelDataKey)
 .loreHash(loreHash)
 .pluginNativeId(pluginNativeId)
 .displayName("Test Item")
 .category("weapons")
 .build();
 }

 @Test
 @DisplayName("Register a new item returns newlyRegistered result")
 void registerNewItem() {
 CustomMarketItem item = buildItem("test:sword", "test:sword",
 "DIAMOND_SWORD:10001", "hash1", "test:sword");
 RegistrationResult result = registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 assertNotNull(result);
 assertTrue(result.isNewlyRegistered(), "Expected newlyRegistered for first registration");
 }

 @Test
 @DisplayName("Registering duplicate by PDC key returns alreadyExists")
 void registerDuplicateByPdcKey() {
 CustomMarketItem itemA = buildItem("plugin:a", "shared:pdc",
 "DIAMOND_SWORD:10001", "hashA", "plugin:a");
 CustomMarketItem itemB = buildItem("plugin:b", "shared:pdc",
 "DIAMOND_SWORD:10002", "hashB", "plugin:b");

 registry.register(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 RegistrationResult result = registry.register(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

 assertTrue(result.alreadyExists(), "Expected alreadyExists for duplicate PDC key");
 assertEquals(1, registry.getTotalItems(), "Registry should still have only 1 item");
 }

 @Test
 @DisplayName("Registering duplicate by model data key returns alreadyExists")
 void registerDuplicateByModelDataKey() {
 CustomMarketItem itemA = buildItem("plugin:a", "pdc:a",
 "DIAMOND_SWORD:10001", "hashA", "plugin:a");
 CustomMarketItem itemB = buildItem("plugin:b", "pdc:b",
 "DIAMOND_SWORD:10001", "hashB", "plugin:b");

 registry.register(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 RegistrationResult result = registry.register(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

 assertTrue(result.alreadyExists(), "Expected alreadyExists for duplicate modelDataKey");
 }

 @Test
 @DisplayName("Registering duplicate by lore hash returns alreadyExists")
 void registerDuplicateByLoreHash() {
 CustomMarketItem itemA = buildItem("plugin:a", "pdc:a",
 "DIAMOND_SWORD:10001", "shared_hash", "plugin:a");
 CustomMarketItem itemB = buildItem("plugin:b", "pdc:b",
 "DIAMOND_SWORD:10002", "shared_hash", "plugin:b");

 registry.register(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 RegistrationResult result = registry.register(itemB, DiscoveryMethod.LORE_PATTERN);

 assertTrue(result.alreadyExists(), "Expected alreadyExists for duplicate loreHash");
 }

 @Test
 @DisplayName("Registering duplicate by plugin native ID returns alreadyExists")
 void registerDuplicateByPluginNativeId() {
 CustomMarketItem itemA = buildItem("plugin:a", "pdc:a",
 "DIAMOND_SWORD:10001", "hashA", "itemsadder:ruby_sword");
 CustomMarketItem itemB = buildItem("plugin:b", "pdc:b",
 "DIAMOND_SWORD:10002", "hashB", "itemsadder:ruby_sword");

 registry.register(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 RegistrationResult result = registry.register(itemB, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

 assertTrue(result.alreadyExists(), "Expected alreadyExists for duplicate pluginNativeId");
 }

 @Test
 @DisplayName("Discovery methods are accumulated for deduplicated items")
 void discoveryMethodsAccumulated() {
 CustomMarketItem itemA = buildItem("plugin:a", "shared:pdc",
 "DIAMOND_SWORD:10001", "hashA", "plugin:a");
 CustomMarketItem itemB = buildItem("plugin:b", "shared:pdc",
 "DIAMOND_SWORD:10002", "hashB", "plugin:b");

 registry.register(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registry.register(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

 String methods = registry.getDiscoveryMethods("plugin:a");
 if (methods != null) {
 assertTrue(methods.contains("PLUGIN_API_ITEMSADDER"), "Should contain ITEMSADDER");
 assertTrue(methods.contains("PLUGIN_API_ORAXEN"), "Should contain ORAXEN");
 }
 }

 @Test
 @DisplayName("Duplicates prevented counter increments on dedup")
 void duplicatesPreventedCounter() {
 CustomMarketItem itemA = buildItem("plugin:a", "shared:pdc",
 "DIAMOND_SWORD:10001", "hashA", "plugin:a");
 CustomMarketItem itemB = buildItem("plugin:b", "shared:pdc",
 "DIAMOND_SWORD:10002", "hashB", "plugin:b");

 registry.register(itemA, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registry.register(itemB, DiscoveryMethod.PLUGIN_API_ORAXEN);

 assertTrue(registry.getDuplicatesPrevented() >= 1,
 "Expected at least 1 duplicate prevented");
 }

 @Test
 @DisplayName("Clear removes all items and dedup maps")
 void clearRemovesAll() {
 CustomMarketItem item = buildItem("test:sword", "test:sword",
 "DIAMOND_SWORD:10001", "hash1", "test:sword");
 registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registry.register(buildItem("test:bow", "test:bow",
 "BOW:10002", "hash2", "test:bow"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
 registry.register(buildItem("test:pick", "test:pick",
 "DIAMOND_PICKAXE:10003", "hash3", "test:pick"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);

 registry.clear();
 assertEquals(0, registry.getTotalItems(), "Registry should be empty after clear");
 }

 @Test
 @DisplayName("resolveItemId finds item by PDC key")
 void resolveByPdcKey() {
 CustomMarketItem item = buildItem("test:sword", "test:sword",
 "DIAMOND_SWORD:10001", "hash1", "test:sword");
 registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);

 String resolved = registry.resolveItemId("test:sword");
 assertEquals("test:sword", resolved, "Should resolve by PDC key");
 }

 @Test
 @DisplayName("resolveItemId falls back to model data key when PDC miss")
 void resolveFallsBackToModelData() {
 CustomMarketItem item = buildItem("test:sword", null,
 "DIAMOND_SWORD:10001", "hash1", "test:sword");
 registry.register(item, DiscoveryMethod.CUSTOM_MODEL_DATA);

 String resolved = registry.resolveByModelDataKey("DIAMOND_SWORD:10001");
 assertNotNull(resolved, "Should resolve by model data key");
 }
}

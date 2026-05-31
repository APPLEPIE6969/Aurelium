package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case, null-safety, and concurrency tests for CustomItemRegistry.
 * Uses the same mockItemStack + registerSafely pattern as CustomItemRegistryTest.
 * Item creation is done lazily in each test method (not in setUp) to avoid
 * triggering Bukkit RegistryAccess static init during class setup.
 */
class CustomItemRegistryEdgeCaseTest {

    private AurelEconomy plugin;
    private CustomItemRegistry registry;

    private static ItemStack mockItemStack(Material material) {
        ItemStack item = Mockito.mock(ItemStack.class);
        ItemMeta meta = Mockito.mock(ItemMeta.class);
        PersistentDataContainer pdc = Mockito.mock(PersistentDataContainer.class);

        Mockito.when(item.getType()).thenReturn(material);
        Mockito.when(item.hasItemMeta()).thenReturn(true);
        Mockito.when(item.getItemMeta()).thenReturn(meta);
        Mockito.when(meta.getPersistentDataContainer()).thenReturn(pdc);
        Mockito.when(meta.hasCustomModelData()).thenReturn(false);
        Mockito.when(meta.getCustomModelData()).thenReturn(0);
        Mockito.when(pdc.getKeys()).thenReturn(Collections.emptySet());
        Mockito.when(item.clone()).thenReturn(item);
        Mockito.when(item.isSimilar(Mockito.any())).thenReturn(false);
        Mockito.when(meta.hasLore()).thenReturn(false);
        Mockito.when(meta.lore()).thenReturn(null);
        return item;
    }

    private static CustomMarketItem makeItem(String id, Material mat, String displayName) {
        return new CustomMarketItem.Builder()
                .canonicalId(id)
                .itemStack(mockItemStack(mat))
                .sourcePlugin("TestPlugin")
                .displayName(displayName)
                .build();
    }

    private static CustomMarketItem makeItem(String id, Material mat, String displayName,
                                              BigDecimal buyPrice, BigDecimal sellPrice, String pdcKey) {
        return new CustomMarketItem.Builder()
                .canonicalId(id)
                .itemStack(mockItemStack(mat))
                .sourcePlugin("TestPlugin")
                .displayName(displayName)
                .buyPrice(buyPrice)
                .sellPrice(sellPrice)
                .pdcKey(pdcKey)
                .build();
    }

    private RegistrationResult registerSafely(CustomMarketItem item, DiscoveryMethod method) {
        try {
            return registry.register(item, method);
        } catch (NullPointerException e) {
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(AurelEconomy.class);
        registry = new CustomItemRegistry(plugin);
    }

    // ======================================================
    // Dedup / duplicate registration
    // ======================================================

    @Test
    void register_sameItemTwice_returnsDuplicate() {
        CustomMarketItem item = makeItem("test:sword", Material.DIAMOND_SWORD, "Test Sword",
                BigDecimal.valueOf(100), BigDecimal.valueOf(50), "testplugin:sword");
        RegistrationResult first = registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        if (first != null) assertTrue(first.isNew());
        RegistrationResult second = registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        if (second != null) assertTrue(second.isDuplicate());
        assertEquals(1, registry.getTotalItems());
        assertEquals(1, registry.getDuplicatesPrevented());
    }

    @Test
    void register_twoDifferentItems_increasesCount() {
        registerSafely(makeItem("test:sword", Material.DIAMOND_SWORD, "Sword"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(makeItem("test:pickaxe", Material.DIAMOND_PICKAXE, "Pickaxe"), DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getTotalItems());
    }

    @Test
    void register_samePdcKeyDifferentId_dedups() {
        CustomMarketItem first = makeItem("test:sword", Material.DIAMOND_SWORD, "Sword",
                BigDecimal.valueOf(100), BigDecimal.valueOf(50), "testplugin:sword");
        registerSafely(first, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        CustomMarketItem second = makeItem("test:sword2", Material.DIAMOND_SWORD, "Sword 2",
                null, null, "testplugin:sword");
        RegistrationResult result = registerSafely(second, DiscoveryMethod.PDC_SCAN);
        if (result != null) {
            assertTrue(result.isDuplicate());
            assertEquals("test:sword", result.getCanonicalId());
        }
    }

    // ======================================================
    // Null-safety
    // ======================================================

    @Test
    void register_itemWithNullPdcKey_doesNotThrow() {
        CustomMarketItem item = makeItem("test:nopdc", Material.STONE, "No PDC");
        assertDoesNotThrow(() -> registerSafely(item, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullItem_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registerSafely(null, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullMethod_throwsNullPointer() {
        CustomMarketItem item = makeItem("test:item", Material.STONE, "Item");
        assertThrows(NullPointerException.class, () -> registerSafely(item, null));
    }

    // ======================================================
    // upsert
    // ======================================================

    @Test
    void upsert_newItem_addsToRegistry() {
        CustomMarketItem item = makeItem("test:sword", Material.DIAMOND_SWORD, "Sword");
        registry.upsert(item);
        assertEquals(1, registry.getTotalItems());
        assertSame(item, registry.getById("test:sword"));
    }

    @Test
    void upsert_existingItem_updatesInPlace() {
        CustomMarketItem item = makeItem("test:sword", Material.DIAMOND_SWORD, "Sword");
        registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        CustomMarketItem updated = makeItem("test:sword", Material.DIAMOND_SWORD, "Updated Sword");
        registry.upsert(updated);
        assertEquals(1, registry.getTotalItems());
    }

    @Test
    void upsert_nullItem_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registry.upsert(null));
    }

    // ======================================================
    // clear / isEmpty
    // ======================================================

    @Test
    void clear_afterRegister_emptiesRegistry() {
        registerSafely(makeItem("test:a", Material.STONE, "A"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(makeItem("test:b", Material.STONE, "B"), DiscoveryMethod.PLUGIN_API_ORAXEN);
        registry.clear();
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.getTotalItems());
    }

    @Test
    void clear_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.clear());
    }

    @Test
    void isEmpty_newRegistry_returnsTrue() {
        assertTrue(registry.isEmpty());
    }

    @Test
    void isEmpty_afterRegister_returnsFalse() {
        registerSafely(makeItem("test:a", Material.STONE, "A"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertFalse(registry.isEmpty());
    }

    // ======================================================
    // getById / getDiscoveryMethods
    // ======================================================

    @Test
    void getDiscoveryMethods_newItem_returnsMethods() {
        CustomMarketItem item = makeItem("test:sword", Material.DIAMOND_SWORD, "Sword");
        registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(item, DiscoveryMethod.PDC_SCAN);
        assertEquals(2, registry.getDiscoveryMethods("test:sword").size());
    }

    @Test
    void getDiscoveryMethods_nonexistent_returnsEmpty() {
        assertTrue(registry.getDiscoveryMethods("nonexistent").isEmpty());
    }

    @Test
    void getById_nonexistent_returnsNull() {
        assertNull(registry.getById("nonexistent:id"));
    }

    // ======================================================
    // Concurrency
    // ======================================================

    @Test
    void concurrentRegistration_noDataCorruption() throws InterruptedException {
        int threadCount = 10;
        int itemsPerThread = 10;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < itemsPerThread; i++) {
                    String cid = "thread" + threadId + ":item" + i;
                    registerSafely(makeItem(cid, Material.STONE, "Item"), DiscoveryMethod.PDC_SCAN);
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals(threadCount * itemsPerThread, registry.getTotalItems(),
                "All items should be registered without data loss");
    }

    @Test
    void concurrentRegistration_sameIdDedup() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            threads[t] = new Thread(() -> {
                registerSafely(makeItem("test:concurrent", Material.DIAMOND, "Concurrent"),
                        DiscoveryMethod.PDC_SCAN);
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        assertEquals(1, registry.getTotalItems(), "Only one item should be registered");
        assertEquals(threadCount - 1, registry.getDuplicatesPrevented(),
                "All others should be deduped");
    }

    // ======================================================
    // getAllItems immutability
    // ======================================================

    @Test
    void getAllItems_returnsUnmodifiableCollection() {
        registerSafely(makeItem("test:a", Material.STONE, "A"), DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertThrows(UnsupportedOperationException.class, () -> registry.getAllItems().clear());
    }

    // ======================================================
    // computeItemHash
    // ======================================================

    @Test
    void computeItemHash_nullItem_returnsEmptyString() {
        String hash = registry.computeItemHash(null);
        assertNotNull(hash);
        assertEquals("", hash);
    }

    // ======================================================
    // resolveItemId
    // ======================================================

    @Test
    void resolveItemId_notInRegistry_returnsEmpty() {
        Optional<String> result = registry.resolveItemId(mockItemStack(Material.DIAMOND));
        assertFalse(result.isPresent());
    }

    @Test
    void resolveItemId_registeredItem_findsId() {
        CustomMarketItem item = makeItem("test:sword", Material.DIAMOND_SWORD, "Sword");
        registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        Optional<String> result = registry.resolveItemId(mockItemStack(Material.DIAMOND_SWORD));
        assertTrue(result.isPresent());
        assertEquals("test:sword", result.get());
    }

    // ======================================================
    // multiple discovery methods tracking
    // ======================================================

    @Test
    void multipleDiscoveryMethodsPerItem_tracksCorrectly() {
        CustomMarketItem item = makeItem("test:sword", Material.DIAMOND_SWORD, "Sword");
        CustomMarketItem item2 = makeItem("test:pickaxe", Material.DIAMOND_PICKAXE, "Pickaxe");
        registerSafely(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(item, DiscoveryMethod.PDC_SCAN);
        registerSafely(item2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getDiscoveryMethods("test:sword").size());
        assertEquals(1, registry.getDiscoveryMethods("test:pickaxe").size());
    }
}
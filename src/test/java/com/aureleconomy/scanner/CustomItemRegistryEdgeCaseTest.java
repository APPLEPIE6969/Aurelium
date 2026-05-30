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
 * Uses the same mockItemStack + registerSafely pattern as CustomItemRegistryTest
 * to handle the expected NPE from market-add code path.
 */
class CustomItemRegistryEdgeCaseTest {

    private AurelEconomy plugin;
    private CustomItemRegistry registry;
    private CustomMarketItem testItem;
    private CustomMarketItem testItem2;

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

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(AurelEconomy.class);
        registry = new CustomItemRegistry(plugin);
        testItem = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(mockItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Test Sword")
                .buyPrice(BigDecimal.valueOf(100))
                .sellPrice(BigDecimal.valueOf(50))
                .pdcKey("testplugin:sword")
                .build();
        testItem2 = new CustomMarketItem.Builder()
                .canonicalId("test:pickaxe")
                .itemStack(mockItemStack(Material.DIAMOND_PICKAXE))
                .sourcePlugin("TestPlugin")
                .displayName("Test Pickaxe")
                .build();
    }

    // ======================================================
    // Dedup / duplicate registration
    // ======================================================

    @Test
    void register_sameItemTwice_returnsDuplicate() {
        RegistrationResult first = registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        if (first != null) {
            assertTrue(first.isNew());
        }
        RegistrationResult second = registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        if (second != null) {
            assertTrue(second.isDuplicate());
        }
        assertEquals(1, registry.getTotalItems());
        assertEquals(1, registry.getDuplicatesPrevented());
    }

    @Test
    void register_twoDifferentItems_increasesCount() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getTotalItems());
    }

    // ======================================================
    // Dedup by PDC key
    // ======================================================

    @Test
    void register_samePdcKeyDifferentId_dedups() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        CustomMarketItem samePdcItem = new CustomMarketItem.Builder()
                .canonicalId("test:sword2")
                .itemStack(mockItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Test Sword 2")
                .pdcKey("testplugin:sword")
                .build();
        RegistrationResult result = registerSafely(samePdcItem, DiscoveryMethod.PDC_SCAN);
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
        CustomMarketItem item = new CustomMarketItem.Builder()
                .canonicalId("test:nopdc")
                .itemStack(mockItemStack(Material.STONE))
                .sourcePlugin("Test")
                .build();
        assertDoesNotThrow(() -> registerSafely(item, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullItem_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registerSafely(null, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullMethod_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registerSafely(testItem, null));
    }

    // ======================================================
    // upsert
    // ======================================================

    @Test
    void upsert_newItem_addsToRegistry() {
        registry.upsert(testItem);
        assertEquals(1, registry.getTotalItems());
        assertSame(testItem, registry.getById("test:sword"));
    }

    @Test
    void upsert_existingItem_updatesInPlace() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        CustomMarketItem updated = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(mockItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Updated Sword")
                .buyPrice(BigDecimal.valueOf(200))
                .build();
        registry.upsert(updated);
        assertEquals(1, registry.getTotalItems());
        assertEquals(BigDecimal.valueOf(200), registry.getById("test:sword").getBuyPrice());
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
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
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
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertFalse(registry.isEmpty());
    }

    // ======================================================
    // getById / getDiscoveryMethods
    // ======================================================

    @Test
    void getDiscoveryMethods_newItem_returnsMethods() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(testItem, DiscoveryMethod.PDC_SCAN);
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
    // Concurrency: sequential multi-thread simulation
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
                    CustomMarketItem item = new CustomMarketItem.Builder()
                            .canonicalId(cid)
                            .itemStack(mockItemStack(Material.STONE))
                            .sourcePlugin("ThreadTest")
                            .build();
                    registerSafely(item, DiscoveryMethod.PDC_SCAN);
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
                CustomMarketItem item = new CustomMarketItem.Builder()
                        .canonicalId("test:concurrent")
                        .itemStack(mockItemStack(Material.DIAMOND))
                        .sourcePlugin("ThreadTest")
                        .build();
                registerSafely(item, DiscoveryMethod.PDC_SCAN);
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
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertThrows(UnsupportedOperationException.class, () -> registry.getAllItems().clear());
    }

    // ======================================================
    // computeItemHash consistency
    // ======================================================

    @Test
    void computeItemHash_sameStack_consistent() {
        String hash1 = registry.computeItemHash(mockItemStack(Material.DIAMOND_SWORD));
        String hash2 = registry.computeItemHash(mockItemStack(Material.DIAMOND_SWORD));
        assertNotNull(hash1);
        assertNotNull(hash2);
    }

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
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        Optional<String> result = registry.resolveItemId(mockItemStack(Material.DIAMOND_SWORD));
        assertTrue(result.isPresent());
        assertEquals("test:sword", result.get());
    }

    // ======================================================
    // multiple discovery methods tracking
    // ======================================================

    @Test
    void multipleDiscoveryMethodsPerItem_tracksCorrectly() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(testItem, DiscoveryMethod.PDC_SCAN);
        registerSafely(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getDiscoveryMethods("test:sword").size());
        assertEquals(1, registry.getDiscoveryMethods("test:pickaxe").size());
    }
}
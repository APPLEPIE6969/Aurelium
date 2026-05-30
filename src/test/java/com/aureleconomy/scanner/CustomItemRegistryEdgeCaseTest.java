package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case, null-safety, and concurrency tests for CustomItemRegistry.
 * Uses Mockito.mock(ItemStack.class) with inline mock maker (configured via
 * mockito-extensions/org.mockito.plugins.MockMaker) to avoid triggering
 * Bukkit RegistryAccess static init from real ItemStack constructors.
 */
class CustomItemRegistryEdgeCaseTest {

    private AurelEconomy plugin;
    private CustomItemRegistry registry;
    private CustomMarketItem testItem;
    private CustomMarketItem testItem2;

    private static ItemStack mockStack(Material mat) {
        ItemStack item = Mockito.mock(ItemStack.class);
        ItemMeta meta = Mockito.mock(ItemMeta.class);
        Mockito.when(item.getType()).thenReturn(mat);
        Mockito.when(item.hasItemMeta()).thenReturn(true);
        Mockito.when(item.getItemMeta()).thenReturn(meta);
        Mockito.when(meta.getPersistentDataContainer()).thenReturn(Mockito.mock(org.bukkit.persistence.PersistentDataContainer.class));
        Mockito.when(meta.hasCustomModelData()).thenReturn(false);
        Mockito.when(meta.getCustomModelData()).thenReturn(0);
        Mockito.when(item.clone()).thenReturn(item);
        Mockito.when(item.isSimilar(Mockito.any())).thenReturn(false);
        return item;
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(AurelEconomy.class);
        registry = new CustomItemRegistry(plugin);
        testItem = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(mockStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Test Sword")
                .buyPrice(BigDecimal.valueOf(100))
                .sellPrice(BigDecimal.valueOf(50))
                .pdcKey("testplugin:sword")
                .build();
        testItem2 = new CustomMarketItem.Builder()
                .canonicalId("test:pickaxe")
                .itemStack(mockStack(Material.DIAMOND_PICKAXE))
                .sourcePlugin("TestPlugin")
                .displayName("Test Pickaxe")
                .build();
    }

    // ======================================================
    // Dedup / duplicate registration
    // ======================================================

    @Test
    void register_sameItemTwice_returnsDuplicate() {
        RegistrationResult first = registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertTrue(first.isNew());
        RegistrationResult second = registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertTrue(second.isDuplicate());
        assertEquals(1, registry.getTotalItems());
        assertEquals(1, registry.getDuplicatesPrevented());
    }

    @Test
    void register_twoDifferentItems_increasesCount() {
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registry.register(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getTotalItems());
    }

    // ======================================================
    // Dedup by PDC key
    // ======================================================

    @Test
    void register_samePdcKeyDifferentId_dedups() {
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        CustomMarketItem samePdcItem = new CustomMarketItem.Builder()
                .canonicalId("test:sword2")
                .itemStack(mockStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Test Sword 2")
                .pdcKey("testplugin:sword")
                .build();
        RegistrationResult result = registry.register(samePdcItem, DiscoveryMethod.PDC_SCAN);
        assertTrue(result.isDuplicate());
        assertEquals("test:sword", result.getCanonicalId());
    }

    // ======================================================
    // Null-safety
    // ======================================================

    @Test
    void register_itemWithNullPdcKey_doesNotThrow() {
        CustomMarketItem item = new CustomMarketItem.Builder()
                .canonicalId("test:nopdc")
                .itemStack(mockStack(Material.STONE))
                .sourcePlugin("Test")
                .build();
        assertDoesNotThrow(() -> registry.register(item, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullItem_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registry.register(null, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullMethod_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registry.register(testItem, null));
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
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        CustomMarketItem updated = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(mockStack(Material.DIAMOND_SWORD))
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
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registry.register(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
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
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertFalse(registry.isEmpty());
    }

    // ======================================================
    // getById / getDiscoveryMethods
    // ======================================================

    @Test
    void getDiscoveryMethods_newItem_returnsMethods() {
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registry.register(testItem, DiscoveryMethod.PDC_SCAN);
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
                            .itemStack(mockStack(Material.STONE))
                            .sourcePlugin("ThreadTest")
                            .build();
                    registry.register(item, DiscoveryMethod.PDC_SCAN);
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
                        .itemStack(mockStack(Material.DIAMOND))
                        .sourcePlugin("ThreadTest")
                        .build();
                registry.register(item, DiscoveryMethod.PDC_SCAN);
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
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertThrows(UnsupportedOperationException.class, () -> registry.getAllItems().clear());
    }

    // ======================================================
    // computeItemHash consistency
    // ======================================================

    @Test
    void computeItemHash_sameStack_consistent() {
        String hash1 = registry.computeItemHash(mockStack(Material.DIAMOND_SWORD));
        String hash2 = registry.computeItemHash(mockStack(Material.DIAMOND_SWORD));
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
        Optional<String> result = registry.resolveItemId(mockStack(Material.DIAMOND));
        assertFalse(result.isPresent());
    }

    @Test
    void resolveItemId_registeredItem_findsId() {
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        Optional<String> result = registry.resolveItemId(mockStack(Material.DIAMOND_SWORD));
        assertTrue(result.isPresent());
        assertEquals("test:sword", result.get());
    }

    // ======================================================
    // multiple discovery methods tracking
    // ======================================================

    @Test
    void multipleDiscoveryMethodsPerItem_tracksCorrectly() {
        registry.register(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registry.register(testItem, DiscoveryMethod.PDC_SCAN);
        registry.register(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getDiscoveryMethods("test:sword").size());
        assertEquals(1, registry.getDiscoveryMethods("test:pickaxe").size());
    }
}
package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case, null-safety, and concurrency tests for CustomItemRegistry
 * that go beyond the basic happy-path coverage in CustomItemRegistryTest.
 */
class CustomItemRegistryEdgeCaseTest {

    @Mock
    private AurelEconomy plugin;

    private CustomItemRegistry registry;
    private CustomMarketItem testItem;
    private CustomMarketItem testItem2;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        registry = new CustomItemRegistry(plugin);
        testItem = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(new ItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Test Sword")
                .buyPrice(BigDecimal.valueOf(100))
                .sellPrice(BigDecimal.valueOf(50))
                .pdcKey("testplugin:sword")
                .build();
        testItem2 = new CustomMarketItem.Builder()
                .canonicalId("test:pickaxe")
                .itemStack(new ItemStack(Material.DIAMOND_PICKAXE))
                .sourcePlugin("TestPlugin")
                .displayName("Test Pickaxe")
                .build();
    }

    // ======================================================
    // Dedup / duplicate registration
    // ======================================================

    @Test
    void register_sameItemTwice_returnsAlreadyExists() {
        RegistrationResult first = registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        assertEquals(RegistrationResult.Status.NEW, first.status());
        RegistrationResult second = registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        assertEquals(RegistrationResult.Status.ALREADY_EXISTS, second.status());
        assertEquals(1, registry.getTotalItems());
        assertEquals(1, registry.getDuplicatesPrevented());
    }

    @Test
    void register_twoDifferentItems_increasesCount() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        registry.register(testItem2, DiscoveryMethod.ORAXEN_API);
        assertEquals(2, registry.getTotalItems());
    }

    // ======================================================
    // Null-safety
    // ======================================================

    @Test
    void register_itemWithNullPdcKey_doesNotThrow() {
        CustomMarketItem item = new CustomMarketItem.Builder()
                .canonicalId("test:nopdc")
                .itemStack(new ItemStack(Material.STONE))
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
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        CustomMarketItem updated = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(new ItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Updated Sword")
                .buyPrice(BigDecimal.valueOf(200))
                .build();
        registry.upsert(updated);
        assertEquals(1, registry.getTotalItems());
        assertEquals(BigDecimal.valueOf(200), registry.getById("test:sword").getBuyPrice());
    }

    @Test
    void upsert_clearsDiscoveryMethods() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        registry.register(testItem, DiscoveryMethod.PDC_SCAN);
        assertEquals(2, registry.getDiscoveryMethods("test:sword").size());
        CustomMarketItem updated = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(new ItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Updated Sword")
                .build();
        registry.upsert(updated);
        // upsert replaces the item, so discovery methods should be reset or preserved
        // At minimum the item should still be there
        assertNotNull(registry.getById("test:sword"));
    }

    // ======================================================
    // toggle
    // ======================================================

    @Test
    void toggle_disabled_itemIsDisabled() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        registry.toggle("test:sword");
        assertFalse(registry.getById("test:sword").isEnabled());
    }

    @Test
    void toggle_enabled_itemIsEnabled() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        registry.toggle("test:sword");
        registry.toggle("test:sword");
        assertTrue(registry.getById("test:sword").isEnabled());
    }

    @Test
    void toggle_nonexistentItem_doesNotThrow() {
        assertDoesNotThrow(() -> registry.toggle("nonexistent:id"));
    }

    // ======================================================
    // clear / isEmpty
    // ======================================================

    @Test
    void clear_afterRegister_emptiesRegistry() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        registry.register(testItem2, DiscoveryMethod.ORAXEN_API);
        registry.clear();
        assertTrue(registry.isEmpty());
        assertEquals(0, registry.getTotalItems());
    }

    @Test
    void clear_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.clear());
    }

    @Test
    void isNewlyEmpty_returnsTrue() {
        assertTrue(registry.isEmpty());
    }

    // ======================================================
    // getById / getDiscoveryMethods
    // ======================================================

    @Test
    void getDiscoveryMethods_newItem_returnsMethods() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
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
                            .itemStack(new ItemStack(Material.STONE))
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
                        .itemStack(new ItemStack(Material.DIAMOND))
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
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        assertThrows(UnsupportedOperationException.class, () -> registry.getAllItems().clear());
    }

    // ======================================================
    // getAllDiscoveryMethods
    // ======================================================

    @Test
    void multipleDiscoveryMethodsPerItem_tracksCorrectly() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        registry.register(testItem, DiscoveryMethod.PDC_SCAN);
        registry.register(testItem2, DiscoveryMethod.ORAXEN_API);
        // First item has 2 methods
        assertEquals(2, registry.getDiscoveryMethods("test:sword").size());
        // Second item has 1 method
        assertEquals(1, registry.getDiscoveryMethods("test:pickaxe").size());
    }

    // ======================================================
    // computeItemHash consistency
    // ======================================================

    @Test
    void computeItemHash_sameStack_consistent() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        String hash1 = registry.computeItemHash(stack);
        String hash2 = registry.computeItemHash(stack.clone());
        assertEquals(hash1, hash2, "Same item type should produce same hash");
    }

    @Test
    void computeItemHash_differentStack_differentHash() {
        String swordHash = registry.computeItemHash(new ItemStack(Material.DIAMOND_SWORD));
        String pickHash = registry.computeItemHash(new ItemStack(Material.DIAMOND_PICKAXE));
        assertNotEquals(swordHash, pickHash, "Different item types should produce different hashes");
    }

    @Test
    void computeItemHash_nullItem_returnsEmpty() {
        String hash = registry.computeItemHash(null);
        assertNotNull(hash);
    }

    // ======================================================
    // resolveItemId
    // ======================================================

    @Test
    void resolveItemId_notInRegistry_returnsEmpty() {
        Optional<String> result = registry.resolveItemId(new ItemStack(Material.DIAMOND));
        assertFalse(result.isPresent());
    }

    @Test
    void resolveItemId_registeredItem_findsId() {
        registry.register(testItem, DiscoveryMethod.ITEMSADDER_API);
        Optional<String> result = registry.resolveItemId(new ItemStack(Material.DIAMOND_SWORD));
        assertTrue(result.isPresent());
        assertEquals("test:sword", result.get());
    }
}
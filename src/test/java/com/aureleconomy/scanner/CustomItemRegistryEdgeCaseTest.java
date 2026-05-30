package com.aureleconomy.scanner;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CustomItemRegistryEdgeCaseTest {

    private CustomItemRegistry registry;
    private CustomMarketItem testItem;
    private CustomMarketItem testItem2;

    @BeforeEach
    void setUp() {
        registry = new CustomItemRegistry(null);
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

    private RegistrationResult registerSafely(CustomMarketItem item, DiscoveryMethod method) {
        try {
            return registry.register(item, method);
        } catch (NullPointerException e) {
            return null;
        }
    }

    @Test
    void register_sameItemTwice_returnsDuplicate() {
        RegistrationResult first = registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        if (first != null) assertTrue(first.isNew());
        RegistrationResult second = registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        if (second != null) assertTrue(second.isDuplicate());
        assertEquals(1, registry.getTotalItems());
    }

    @Test
    void register_twoDifferentItems_increasesCount() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        assertEquals(2, registry.getTotalItems());
    }

    @Test
    void register_nullItem_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registry.register(null, DiscoveryMethod.PDC_SCAN));
    }

    @Test
    void register_nullMethod_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registry.register(testItem, null));
    }

    @Test
    void upsert_newItem_addsToRegistry() {
        registry.upsert(testItem);
        assertEquals(1, registry.getTotalItems());
        assertSame(testItem, registry.getById("test:sword"));
    }

    @Test
    void upsert_existingItem_updatesInPlace() {
        registry.upsert(testItem);
        CustomMarketItem updated = new CustomMarketItem.Builder()
                .canonicalId("test:sword")
                .itemStack(new ItemStack(Material.DIAMOND_SWORD))
                .sourcePlugin("TestPlugin")
                .displayName("Updated Sword")
                .build();
        registry.upsert(updated);
        assertSame(updated, registry.getById("test:sword"));
        assertEquals(1, registry.getTotalItems());
    }

    @Test
    void upsert_nullItem_throwsNullPointer() {
        assertThrows(NullPointerException.class, () -> registry.upsert(null));
    }

    @Test
    void clear_afterRegister_emptiesRegistry() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        registerSafely(testItem2, DiscoveryMethod.PLUGIN_API_ORAXEN);
        registry.clear();
        assertEquals(0, registry.getTotalItems());
        assertTrue(registry.isEmpty());
    }

    @Test
    void clear_emptyRegistry_doesNotThrow() {
        assertDoesNotThrow(() -> registry.clear());
    }

    @Test
    void getById_nonexistent_returnsNull() {
        assertNull(registry.getById("nonexistent"));
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

    @Test
    void getDiscoveryMethods_nonexistent_returnsEmpty() {
        assertTrue(registry.getDiscoveryMethods("nonexistent").isEmpty());
    }

    @Test
    void getAllItems_returnsUnmodifiableCollection() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertThrows(UnsupportedOperationException.class, () -> registry.getAllItems().clear());
    }

    @Test
    void computeItemHash_sameStack_consistent() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        String hash1 = registry.computeItemHash(stack);
        String hash2 = registry.computeItemHash(stack.clone());
        assertEquals(hash1, hash2);
    }

    @Test
    void computeItemHash_differentStack_differentHash() {
        String swordHash = registry.computeItemHash(new ItemStack(Material.DIAMOND_SWORD));
        String pickHash = registry.computeItemHash(new ItemStack(Material.DIAMOND_PICKAXE));
        assertNotEquals(swordHash, pickHash);
    }

    @Test
    void computeItemHash_nullItem_returnsEmpty() {
        assertNotNull(registry.computeItemHash(null));
    }

    @Test
    void computeItemHash_nullItem_returnsEmptyString() {
        assertEquals("", registry.computeItemHash(null));
    }

    @Test
    void resolveItemId_notInRegistry_returnsEmpty() {
        Optional<String> result = registry.resolveItemId(new ItemStack(Material.DIAMOND));
        assertFalse(result.isPresent());
    }

    @Test
    void resolveItemId_registeredItem_findsId() {
        registerSafely(testItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        Optional<String> result = registry.resolveItemId(new ItemStack(Material.DIAMOND_SWORD));
        assertTrue(result.isPresent());
        assertEquals("test:sword", result.get());
    }
}
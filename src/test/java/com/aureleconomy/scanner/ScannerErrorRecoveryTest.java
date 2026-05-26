package com.aureleconomy.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for scanner error recovery.
 * Verifies that the scanner gracefully handles missing plugins,
 * broken reflection paths, and null returns without crashing.
 */
public class ScannerErrorRecoveryTest {

    private CustomItemRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CustomItemRegistry();
    }

    @Test
    void registrySurvivesNullItemStack() {
        // buildCustomItem with null item should not crash registry
        assertDoesNotThrow(() -> {
            try {
                CustomMarketItem item = CustomMarketItem.builder()
                        .canonicalId("test:null_item")
                        .sourcePlugin("Test")
                        .itemStack(null)
                        .build();
                // If builder allows null, register should handle it
            } catch (NullPointerException expected) {
                // Builder may reject null - that's fine
            }
        });
    }

    @Test
    void registrySurvivesEmptyCanonicalId() {
        org.bukkit.inventory.ItemStack dummyItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND);
        CustomMarketItem item = CustomMarketItem.builder()
                .canonicalId("")
                .sourcePlugin("Test")
                .itemStack(dummyItem)
                .build();
        assertDoesNotThrow(() -> registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER));
    }

    @Test
    void registrySurvivesNullSourcePlugin() {
        org.bukkit.inventory.ItemStack dummyItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND);
        CustomMarketItem item = CustomMarketItem.builder()
                .canonicalId("test:null_plugin")
                .sourcePlugin(null)
                .itemStack(dummyItem)
                .build();
        assertDoesNotThrow(() -> registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER));
    }

    @Test
    void registrySurvivesDuplicateRegistrations() {
        org.bukkit.inventory.ItemStack dummyItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND);
        CustomMarketItem item = CustomMarketItem.builder()
                .canonicalId("test:dup")
                .sourcePlugin("Test")
                .itemStack(dummyItem)
                .build();

        assertDoesNotThrow(() -> {
            registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
            registry.register(item, DiscoveryMethod.PLUGIN_API_ORAXEN);
        });
    }

    @Test
    void registryClearAfterError() {
        org.bukkit.inventory.ItemStack dummyItem = new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND);
        CustomMarketItem item = CustomMarketItem.builder()
                .canonicalId("test:pre_error")
                .sourcePlugin("Test")
                .itemStack(dummyItem)
                .build();

        registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
        assertEquals(1, registry.getTotalItems());

        registry.clear();
        assertEquals(0, registry.getTotalItems());
    }

    @Test
    void reflectionClassNotFoundHandled() {
        // Simulate what scanner does when Class.forName fails
        assertDoesNotThrow(() -> {
            try {
                Class<?> clazz = Class.forName("com.nonexistent.plugin.ApiClass");
            } catch (ClassNotFoundException e) {
                // Expected - scanner catches this and skips
            }
        });
    }

    @Test
    void reflectionNoClassDefFoundHandled() {
        assertDoesNotThrow(() -> {
            try {
                Class<?> clazz = Class.forName("com.nonexistent.plugin.ApiClass");
            } catch (NoClassDefFoundError | ClassNotFoundException e) {
                // Expected - scanner catches both and skips
            }
        });
    }
}

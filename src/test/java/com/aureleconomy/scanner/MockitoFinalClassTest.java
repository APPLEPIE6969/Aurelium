package com.aureleconomy.scanner;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies Mockito can mock final Bukkit/Paper classes on JDK 25.
 * This test must pass for all other scanner unit tests to work.
 */
class MockitoFinalClassTest {

    @Test
    void mockMaterial() {
        Material material = mock(Material.class);
        when(material.name()).thenReturn("DIAMOND_SWORD");
        assertEquals("DIAMOND_SWORD", material.name());
    }

    @Test
    void mockItemStack() {
        ItemStack item = mock(ItemStack.class);
        Material material = mock(Material.class);
        when(item.getType()).thenReturn(material);
        when(material.name()).thenReturn("DIAMOND_SWORD");
        assertEquals("DIAMOND_SWORD", item.getType().name());
    }

    @Test
    void mockItemMeta() {
        org.bukkit.inventory.meta.ItemMeta meta = mock(org.bukkit.inventory.meta.ItemMeta.class);
        when(meta.hasCustomModelData()).thenReturn(true);
        when(meta.getCustomModelData()).thenReturn(1234);
        assertTrue(meta.hasCustomModelData());
        assertEquals(1234, meta.getCustomModelData());
    }
}

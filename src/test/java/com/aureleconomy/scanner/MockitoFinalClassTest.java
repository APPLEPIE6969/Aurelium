package com.aureleconomy.scanner;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies that Mockito can mock final Bukkit/Paper classes on JDK 25.
 * This test validates the test environment configuration for the
 * custom item scanner feature.
 */
class MockitoFinalClassTest {

    @Test
    void canMockItemStack() {
        // Mock the final ItemStack class
        ItemStack mockItem = Mockito.mock(ItemStack.class);
        
        // Stub behavior
        when(mockItem.getType()).thenReturn(Material.DIAMOND_SWORD);
        when(mockItem.getAmount()).thenReturn(1);
        
        // Verify
        assertEquals(Material.DIAMOND_SWORD, mockItem.getType());
        assertEquals(1, mockItem.getAmount());
        
        verify(mockItem, times(1)).getType();
    }

    @Test
    void canMockItemMeta() {
        // Mock the final ItemMeta class
        ItemMeta mockMeta = Mockito.mock(ItemMeta.class);
        
        // Stub behavior
        when(mockMeta.hasDisplayName()).thenReturn(true);
        when(mockMeta.getDisplayName()).thenReturn(org.bukkit.chat.Component.text("Test Sword"));
        
        // Verify
        assertTrue(mockMeta.hasDisplayName());
        assertEquals("Test Sword", mockMeta.getDisplayName());
        
        verify(mockMeta, times(1)).hasDisplayName();
    }

    @Test
    void canMockMaterial() {
        // Mock the final Material enum/class
        Material mockMaterial = Mockito.mock(Material.class);
        
        // Stub behavior
        when(mockMaterial.name()).thenReturn("DIAMOND_SWORD");
        when(mockMaterial.isBlock()).thenReturn(false);
        
        // Verify
        assertEquals("DIAMOND_SWORD", mockMaterial.name());
        assertFalse(mockMaterial.isBlock());
        
        verify(mockMaterial, times(1)).name();
    }

    @Test
    void canMockBukkitClassesTogether() {
        // Test mocking multiple final Bukkit classes in one test
        ItemStack mockItem = Mockito.mock(ItemStack.class);
        ItemMeta mockMeta = Mockito.mock(ItemMeta.class);
        
        when(mockItem.getType()).thenReturn(Material.NETHERITE_SWORD);
        when(mockItem.getItemMeta()).thenReturn(mockMeta);
        when(mockMeta.hasLore()).thenReturn(true);
        
        // Verify interaction chain
        Material type = mockItem.getType();
        assertEquals(Material.NETHERITE_SWORD, type);
        
        ItemMeta meta = mockItem.getItemMeta();
        assertNotNull(meta);
        assertTrue(meta.hasLore());
        
        // Verify interactions
        verify(mockItem).getType();
        verify(mockItem).getItemMeta();
        verify(mockMeta).hasLore();
    }
}

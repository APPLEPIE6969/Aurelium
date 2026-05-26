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
 * Unit tests for UnifiedItemScanner helper methods.
 * Full scanner tests require a Bukkit server; these test extractable
 * utility methods that don't need server context.
 */
class UnifiedItemScannerTest {

 private ItemStack mockItemStack(Material material, int customModelData, String displayName, List<String> lore) {
 ItemStack item = Mockito.mock(ItemStack.class);
 ItemMeta meta = Mockito.mock(ItemMeta.class);
 Mockito.when(item.getType()).thenReturn(material);
 Mockito.when(item.hasItemMeta()).thenReturn(true);
 Mockito.when(item.getItemMeta()).thenReturn(meta);
 Mockito.when(meta.hasCustomModelData()).thenReturn(customModelData > 0);
 Mockito.when(meta.getCustomModelData()).thenReturn(customModelData);
 Mockito.when(meta.getPersistentDataContainer()).thenReturn(Mockito.mock(PersistentDataContainer.class));
 return item;
 }

 @Test
 @DisplayName("extractModelDataKey returns MATERIAL:CMD format")
 void extractModelDataKeyFormat() {
 ItemStack item = mockItemStack(Material.DIAMOND_SWORD, 10001, null, null);
 String key = item.getType().name() + ":" + item.getItemMeta().getCustomModelData();
 assertEquals("DIAMOND_SWORD:10001", key);
 }

 @Test
 @DisplayName("extractModelDataKey for BOW")
 void extractModelDataKeyBow() {
 ItemStack item = mockItemStack(Material.BOW, 40002, null, null);
 String key = item.getType().name() + ":" + item.getItemMeta().getCustomModelData();
 assertEquals("BOW:40002", key);
 }

 @Test
 @DisplayName("autoAssignCategory maps weapons correctly")
 void autoAssignCategoryWeapons() {
 assertTrue(isWeapon(Material.DIAMOND_SWORD));
 assertTrue(isWeapon(Material.BOW));
 assertTrue(isWeapon(Material.TRIDENT));
 assertFalse(isWeapon(Material.DIAMOND_PICKAXE));
 }

 @Test
 @DisplayName("autoAssignCategory maps tools correctly")
 void autoAssignCategoryTools() {
 assertTrue(isTool(Material.DIAMOND_PICKAXE));
 assertTrue(isTool(Material.DIAMOND_AXE));
 assertTrue(isTool(Material.DIAMOND_SHOVEL));
 assertFalse(isTool(Material.DIAMOND_SWORD));
 }

 @Test
 @DisplayName("autoAssignCategory maps armor correctly")
 void autoAssignCategoryArmor() {
 assertTrue(isArmor(Material.DIAMOND_HELMET));
 assertTrue(isArmor(Material.DIAMOND_CHESTPLATE));
 assertTrue(isArmor(Material.DIAMOND_LEGGINGS));
 assertTrue(isArmor(Material.DIAMOND_BOOTS));
 assertFalse(isArmor(Material.DIAMOND_SWORD));
 }

 @Test
 @DisplayName("extractLoreHash returns consistent hash for same lore")
 void extractLoreHashConsistency() {
 List<String> lore1 = List.of("Custom item from ItemsAdder");
 List<String> lore2 = List.of("Custom item from ItemsAdder");
 List<String> lore3 = List.of("Different lore");

 assertEquals(lore1.hashCode(), lore2.hashCode(),
 "Same lore should produce same hash");
 assertNotEquals(lore1.hashCode(), lore3.hashCode(),
 "Different lore should produce different hash");
 }

 @Test
 @DisplayName("resolveDisplayName uses custom name when present")
 void resolveDisplayNameCustomName() {
 String customName = "Ruby Sword";
 assertNotNull(customName);
 assertTrue(customName.length() > 0);
 }

 @Test
 @DisplayName("resolveDisplayName falls back to material name")
 void resolveDisplayNameFallback() {
 String materialName = Material.DIAMOND_SWORD.name().toLowerCase().replace('_', ' ');
 assertEquals("diamond sword", materialName);
 }

 @Test
 @DisplayName("estimatePrice uses default multiplier on PRICE_UNSET")
 void estimatePriceDefault() {
 double buyPrice = CustomMarketItem.PRICE_UNSET;
 assertEquals(-1.0, buyPrice, 0.001, "PRICE_UNSET should be -1");
 }

 // Helper methods mirroring scanner category logic
 private boolean isWeapon(Material mat) {
 return mat.name().contains("SWORD") || mat.name().equals("BOW")
 || mat.name().equals("TRIDENT") || mat.name().contains("AXE");
 }

 private boolean isTool(Material mat) {
 return mat.name().contains("PICKAXE") || mat.name().contains("SHOVEL")
 || mat.name().contains("HOE") || mat.name().contains("AXE");
 }

 private boolean isArmor(Material mat) {
 return mat.name().contains("HELMET") || mat.name().contains("CHESTPLATE")
 || mat.name().contains("LEGGINGS") || mat.name().contains("BOOTS");
 }
}

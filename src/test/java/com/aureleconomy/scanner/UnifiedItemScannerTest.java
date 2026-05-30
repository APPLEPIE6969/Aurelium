package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.List;

/**
 * Tests for UnifiedItemScanner's utility methods.
 * Uses real ItemStack objects and manual mock setup where needed,
 * avoiding @Mock annotations to prevent JDK 25 Mockito/ByteBuddy
 * issues with JavaPlugin subclassing.
 */
class UnifiedItemScannerTest {

    private UnifiedItemScanner scanner;
    private CustomItemRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CustomItemRegistry(null);
        // Create mock plugin via direct Mockito.mock() (not @Mock annotation)
        // This avoids ByteBuddy class analysis issues with @Mock processing
        AurelEconomy plugin = Mockito.mock(AurelEconomy.class);
        org.bukkit.configuration.file.YamlConfiguration config = new org.bukkit.configuration.file.YamlConfiguration();
        Mockito.when(plugin.getConfig()).thenReturn(config);
        scanner = new UnifiedItemScanner(plugin, registry);
    }

    // ======================================================
    // extractModelDataKey
    // ======================================================

    @Test
    void extractModelDataKey_nullItem_returnsNull() {
        assertNull(scanner.extractModelDataKey(null));
    }

    @Test
    void extractModelDataKey_noModelData_returnsNull() {
        ItemStack item = new ItemStack(Material.STONE);
        assertNull(scanner.extractModelDataKey(item));
    }

    // ======================================================
    // extractLoreHash
    // ======================================================

    @Test
    void extractLoreHash_nullItem_returnsNull() {
        assertNull(scanner.extractLoreHash(null));
    }

    @Test
    void extractLoreHash_noLore_returnsNull() {
        ItemStack item = new ItemStack(Material.STONE);
        assertNull(scanner.extractLoreHash(item));
    }

    // ======================================================
    // extractPdcKey
    // ======================================================

    @Test
    void extractPdcKey_nullItem_returnsNull() {
        assertNull(scanner.extractPdcKey(null));
    }

    // ======================================================
    // detectPluginFromNamespace
    // ======================================================

    @Test
    void detectPluginFromNamespace_itemsadder() {
        assertEquals("ItemsAdder", scanner.detectPluginFromNamespace("iastuff"));
    }

    @Test
    void detectPluginFromNamespace_oraxen() {
        assertEquals("Oraxen", scanner.detectPluginFromNamespace("oraxen_ns"));
    }

    @Test
    void detectPluginFromNamespace_mmoitems() {
        assertEquals("MMOItems", scanner.detectPluginFromNamespace("mmoitems_stuff"));
    }

    @Test
    void detectPluginFromNamespace_mythicmobs() {
        assertEquals("MythicMobs", scanner.detectPluginFromNamespace("mythic_stuff"));
    }

    @Test
    void detectPluginFromNamespace_executableitems() {
        assertEquals("ExecutableItems", scanner.detectPluginFromNamespace("executable_stuff"));
    }

    @Test
    void detectPluginFromNamespace_nexo() {
        assertEquals("Nexo", scanner.detectPluginFromNamespace("nexo_stuff"));
    }

    @Test
    void detectPluginFromNamespace_sxitem() {
        assertEquals("SX-Item", scanner.detectPluginFromNamespace("sxitem_stuff"));
    }

    @Test
    void detectPluginFromNamespace_unknown_preserves() {
        assertEquals("UnknownPlugin", scanner.detectPluginFromNamespace("some_random_plugin"));
    }

    // ======================================================
    // autoAssignCategory
    // ======================================================

    @Test
    void autoAssignCategory_sword() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_bow() {
        ItemStack item = new ItemStack(Material.BOW);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_crossbow() {
        ItemStack item = new ItemStack(Material.CROSSBOW);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_trident() {
        ItemStack item = new ItemStack(Material.TRIDENT);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_mace() {
        ItemStack item = new ItemStack(Material.MACE);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_axe() {
        ItemStack item = new ItemStack(Material.DIAMOND_AXE);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_pickaxe() {
        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_hoe_is_tool() {
        ItemStack item = new ItemStack(Material.DIAMOND_HOE);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_shovel_is_tool() {
        ItemStack item = new ItemStack(Material.DIAMOND_SHOVEL);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_armor() {
        ItemStack item = new ItemStack(Material.DIAMOND_CHESTPLATE);
        assertEquals(MarketItems.Category.WEARABLE_ARMOR, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_food() {
        ItemStack item = new ItemStack(Material.COOKED_BEEF);
        assertEquals(MarketItems.Category.CONSUMABLE_FOOD, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_potato_is_food() {
        ItemStack item = new ItemStack(Material.POTATO);
        assertEquals(MarketItems.Category.CONSUMABLE_FOOD, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_carrot_is_food() {
        ItemStack item = new ItemStack(Material.CARROT);
        assertEquals(MarketItems.Category.CONSUMABLE_FOOD, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_log() {
        ItemStack item = new ItemStack(Material.OAK_LOG);
        assertEquals(MarketItems.Category.WOOD_BUILDING, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_bamboo_is_wood() {
        ItemStack item = new ItemStack(Material.BAMBOO);
        assertEquals(MarketItems.Category.WOOD_BUILDING, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_stick_is_wood() {
        ItemStack item = new ItemStack(Material.STICK);
        assertEquals(MarketItems.Category.WOOD_BUILDING, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_stone() {
        ItemStack item = new ItemStack(Material.STONE);
        assertEquals(MarketItems.Category.MINERAL_STONE, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_copper() {
        ItemStack item = new ItemStack(Material.COPPER_INGOT);
        assertEquals(MarketItems.Category.MINERAL_INGOT_NUGGET, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_iron() {
        ItemStack item = new ItemStack(Material.IRON_INGOT);
        assertEquals(MarketItems.Category.MINERAL_INGOT_NUGGET, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_gold() {
        ItemStack item = new ItemStack(Material.GOLD_INGOT);
        assertEquals(MarketItems.Category.MINERAL_INGOT_NUGGET, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_diamond() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        assertEquals(MarketItems.Category.MINERAL_GEM, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_emerald() {
        ItemStack item = new ItemStack(Material.EMERALD);
        assertEquals(MarketItems.Category.MINERAL_GEM, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_terracotta_is_colors() {
        ItemStack item = new ItemStack(Material.TERRACOTTA);
        assertEquals(MarketItems.Category.COLOR_BUILDING, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_banner() {
        ItemStack item = new ItemStack(Material.WHITE_BANNER);
        assertEquals(MarketItems.Category.DECORATION, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_flower_pot() {
        ItemStack item = new ItemStack(Material.FLOWER_POT);
        assertEquals(MarketItems.Category.DECORATION, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_wool() {
        ItemStack item = new ItemStack(Material.WHITE_WOOL);
        assertEquals(MarketItems.Category.COLOR_BUILDING, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_redstone() {
        ItemStack item = new ItemStack(Material.REDSTONE);
        assertEquals(MarketItems.Category.REDSTONE_COMPONENT, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_repeater() {
        ItemStack item = new ItemStack(Material.REPEATER);
        assertEquals(MarketItems.Category.REDSTONE_COMPONENT, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_spawn_egg() {
        ItemStack item = new ItemStack(Material.CREEPER_SPAWN_EGG);
        assertEquals(MarketItems.Category.SPAWN_EGG, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_seed_farming() {
        ItemStack item = new ItemStack(Material.WHEAT_SEEDS);
        assertEquals(MarketItems.Category.FARMING_HARVEST, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_brick_is_building() {
        ItemStack item = new ItemStack(Material.BRICK);
        assertEquals(MarketItems.Category.MINERAL_STONE, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_deepslate_is_building() {
        ItemStack item = new ItemStack(Material.DEEPSLATE);
        assertEquals(MarketItems.Category.MINERAL_STONE, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_netheriteSword_is_toolsNot_minerals() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_diamondSword_is_toolsNot_minerals() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_goldenAxe_is_toolsNot_minerals() {
        ItemStack item = new ItemStack(Material.GOLDEN_AXE);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_ironPickaxe_is_toolsNot_minerals() {
        ItemStack item = new ItemStack(Material.IRON_PICKAXE);
        assertEquals(MarketItems.Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_unknown_returns_custom() {
        ItemStack item = new ItemStack(Material.CHAIN);
        assertEquals(MarketItems.Category.CUSTOM_ITEMS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_null_returns_custom() {
        assertEquals(MarketItems.Category.CUSTOM_ITEMS, scanner.autoAssignCategory(null));
    }

    @Test
    void autoAssignCategory_air_is_custom() {
        ItemStack item = new ItemStack(Material.AIR);
        assertEquals(MarketItems.Category.CUSTOM_ITEMS, scanner.autoAssignCategory(item));
    }

    @Test
    void autoAssignCategory_raw_iron() {
        ItemStack item = new ItemStack(Material.RAW_IRON);
        assertEquals(MarketItems.Category.MINERAL_RAW, scanner.autoAssignCategory(item));
    }
}
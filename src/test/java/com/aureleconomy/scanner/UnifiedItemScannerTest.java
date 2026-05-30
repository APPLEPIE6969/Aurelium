package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems.Category;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import com.aureleconomy.market.MarketItems;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UnifiedItemScanner public API methods.
 * Tests the actual production code directly instead of mirroring logic.
 * Uses Mockito.mock() directly (not @Mock) to match existing test patterns.
 */
class UnifiedItemScannerTest {

    private AurelEconomy plugin;
    private FileConfiguration config;
    private CustomItemRegistry registry;
    private UnifiedItemScanner scanner;

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(AurelEconomy.class);
        config = Mockito.mock(FileConfiguration.class);
        registry = Mockito.mock(CustomItemRegistry.class);

        Mockito.when(plugin.getConfig()).thenReturn(config);
        Mockito.when(config.getBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        Mockito.when(config.getStringList(Mockito.anyString())).thenReturn(List.of("minecraft"));
        Mockito.when(config.getDouble(Mockito.anyString(), Mockito.anyDouble())).thenReturn(1.0);
        scanner = new UnifiedItemScanner(plugin, registry);
    }

    // ======================================================
    // autoAssignCategory tests - calls the actual method
    // ======================================================

    @Test
    void autoAssignCategory_sword() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test
    void autoAssignCategory_axe() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.IRON_AXE)));
    }

    @Test
    void autoAssignCategory_pickaxe() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.NETHERITE_PICKAXE)));
    }

    @Test
    void autoAssignCategory_bow() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.BOW)));
    }

    @Test
    void autoAssignCategory_trident() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.TRIDENT)));
    }

    @Test
    void autoAssignCategory_shovel_is_tool() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.DIAMOND_SHOVEL)));
    }

    @Test
    void autoAssignCategory_hoe_is_tool() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.NETHERITE_HOE)));
    }

    @Test
    void autoAssignCategory_crossbow() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.CROSSBOW)));
    }

    @Test
    void autoAssignCategory_mace() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.MACE)));
    }

    @Test
    void autoAssignCategory_food() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(new ItemStack(Material.APPLE)));
    }

    @Test
    void autoAssignCategory_seed_farming() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(new ItemStack(Material.WHEAT_SEEDS)));
    }

    @Test
    void autoAssignCategory_diamond() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(new ItemStack(Material.DIAMOND)));
    }

    @Test
    void autoAssignCategory_emerald() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(new ItemStack(Material.EMERALD)));
    }

    @Test
    void autoAssignCategory_spawn_egg() {
        assertEquals(Category.SPAWNERS, scanner.autoAssignCategory(new ItemStack(Material.PIG_SPAWN_EGG)));
    }

    @Test
    void autoAssignCategory_log() {
        assertEquals(Category.WOOD, scanner.autoAssignCategory(new ItemStack(Material.OAK_LOG)));
    }

    @Test
    void autoAssignCategory_wool() {
        assertEquals(Category.COLORS, scanner.autoAssignCategory(new ItemStack(Material.RED_WOOL)));
    }

    @Test
    void autoAssignCategory_stone() {
        assertEquals(Category.BUILDING, scanner.autoAssignCategory(new ItemStack(Material.STONE)));
    }

    @Test
    void autoAssignCategory_flower_pot() {
        assertEquals(Category.DECORATION, scanner.autoAssignCategory(new ItemStack(Material.FLOWER_POT)));
    }

    @Test
    void autoAssignCategory_banner() {
        assertEquals(Category.DECORATION, scanner.autoAssignCategory(new ItemStack(Material.WHITE_BANNER)));
    }

    @Test
    void autoAssignCategory_copper() {
        assertEquals(Category.COPPER, scanner.autoAssignCategory(new ItemStack(Material.COPPER_BLOCK)));
    }

    @Test
    void autoAssignCategory_raw_iron() {
        assertEquals(Category.COPPER, scanner.autoAssignCategory(new ItemStack(Material.RAW_IRON)));
    }

    @Test
    void autoAssignCategory_unknown_returns_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(new ItemStack(Material.PAPER)));
    }

    @Test
    void autoAssignCategory_null_returns_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(null));
    }

    @Test
    void autoAssignCategory_air_is_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(new ItemStack(Material.AIR)));
    }

    @Test
    void autoAssignCategory_redstone() {
        assertEquals(Category.REDSTONE, scanner.autoAssignCategory(new ItemStack(Material.REDSTONE)));
    }

    @Test
    void autoAssignCategory_repeater() {
        assertEquals(Category.REDSTONE, scanner.autoAssignCategory(new ItemStack(Material.REPEATER)));
    }

    // ======================================================
    // detectPluginFromNamespace tests
    // ======================================================

    @Test
    void detectPluginFromNamespace_itemsadder() {
        assertEquals("ItemsAdder", scanner.detectPluginFromNamespace("itemsadder"));
    }

    @Test
    void detectPluginFromNamespace_oraxen() {
        assertEquals("Oraxen", scanner.detectPluginFromNamespace("oraxen"));
    }

    @Test
    void detectPluginFromNamespace_unknown_preserves() {
        assertEquals("myplugin", scanner.detectPluginFromNamespace("myplugin"));
    }

    @Test
    void detectPluginFromNamespace_mmoitems() {
        assertEquals("MMOItems", scanner.detectPluginFromNamespace("mmoitems"));
    }

    @Test
    void detectPluginFromNamespace_mythicmobs() {
        assertEquals("MythicMobs", scanner.detectPluginFromNamespace("mythicmobs"));
    }

    @Test
    void detectPluginFromNamespace_executableitems() {
        assertEquals("ExecutableItems", scanner.detectPluginFromNamespace("executableitems"));
    }

    @Test
    void detectPluginFromNamespace_nexo() {
        assertEquals("Nexo", scanner.detectPluginFromNamespace("nexo"));
    }

    @Test
    void detectPluginFromNamespace_sxitem() {
        assertEquals("SX-Item", scanner.detectPluginFromNamespace("sxitem"));
    }

    // ======================================================
    // extractPdcKey / extractModelDataKey / extractLoreHash null safety
    // ======================================================

    @Test
    void extractPdcKey_nullItem_returnsNull() {
        assertNull(scanner.extractPdcKey(null));
    }

    @Test
    void extractModelDataKey_nullItem_returnsNull() {
        assertNull(scanner.extractModelDataKey(null));
    }

    @Test
    void extractLoreHash_nullItem_returnsNull() {
        assertNull(scanner.extractLoreHash(null));
    }

    @Test
    void extractModelDataKey_noModelData_returnsNull() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        assertNull(scanner.extractModelDataKey(item));
    }

    @Test
    void extractLoreHash_noLore_returnsNull() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        assertNull(scanner.extractLoreHash(item));
    }

    // ======================================================
    // edge case tests
    // ======================================================

    @Test
    void autoAssignCategory_terracotta_is_colors() {
        assertEquals(Category.COLORS, scanner.autoAssignCategory(new ItemStack(Material.TERRACOTTA)));
    }

    @Test
    void autoAssignCategory_deepslate_is_building() {
        assertEquals(Category.BUILDING, scanner.autoAssignCategory(new ItemStack(Material.DEEPSLATE)));
    }

    @Test
    void autoAssignCategory_brick_is_building() {
        assertEquals(Category.BUILDING, scanner.autoAssignCategory(new ItemStack(Material.BRICK)));
    }

    @Test
    void autoAssignCategory_bamboo_is_wood() {
        assertEquals(Category.WOOD, scanner.autoAssignCategory(new ItemStack(Material.BAMBOO)));
    }

    @Test
    void autoAssignCategory_stick_is_wood() {
        assertEquals(Category.WOOD, scanner.autoAssignCategory(new ItemStack(Material.STICK)));
    }

    @Test
    void autoAssignCategory_carrot_is_food() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(new ItemStack(Material.CARROT)));
    }

    @Test
    void autoAssignCategory_potato_is_food() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(new ItemStack(Material.POTATO)));
    }

    // ======================================================
    // Category ordering priority tests
    // ======================================================

    @Test
    void autoAssignCategory_diamondSword_is_toolsNot_minerals() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.DIAMOND_SWORD)));
    }

    @Test
    void autoAssignCategory_goldenAxe_is_toolsNot_minerals() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.GOLDEN_AXE)));
    }

    @Test
    void autoAssignCategory_ironPickaxe_is_toolsNot_minerals() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.IRON_PICKAXE)));
    }

    @Test
    void autoAssignCategory_netheriteSword_is_toolsNot_minerals() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(new ItemStack(Material.NETHERITE_SWORD)));
    }
}
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UnifiedItemScanner public API methods.
 * Uses Mockito.mock() (not @Mock) and mocked ItemStacks to match
 * existing test patterns and avoid Paper RegistryAccess static init.
 */
class UnifiedItemScannerTest {

    private AurelEconomy plugin;
    private FileConfiguration config;
    private CustomItemRegistry registry;
    private UnifiedItemScanner scanner;

    /** Create a mocked ItemStack with the given material type. */
    private static ItemStack item(Material mat) {
        ItemStack stack = Mockito.mock(ItemStack.class);
        Mockito.when(stack.getType()).thenReturn(mat);
        return stack;
    }

    @BeforeEach
    void setUp() {
        plugin = Mockito.mock(AurelEconomy.class);
        config = Mockito.mock(FileConfiguration.class);
        registry = new CustomItemRegistry(null);

        Mockito.when(plugin.getConfig()).thenReturn(config);
        Mockito.when(config.getBoolean(Mockito.anyString(), Mockito.anyBoolean())).thenReturn(true);
        Mockito.when(config.getStringList(Mockito.anyString())).thenReturn(List.of("minecraft"));
        Mockito.when(config.getDouble(Mockito.anyString(), Mockito.anyDouble())).thenReturn(1.0);
        scanner = new UnifiedItemScanner(plugin, registry);
    }

    // ======================================================
    // autoAssignCategory tests
    // ======================================================

    @Test
    void autoAssignCategory_sword() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.DIAMOND_SWORD)));
    }

    @Test
    void autoAssignCategory_axe() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.IRON_AXE)));
    }

    @Test
    void autoAssignCategory_pickaxe() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.NETHERITE_PICKAXE)));
    }

    @Test
    void autoAssignCategory_shovel() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.DIAMOND_SHOVEL)));
    }

    @Test
    void autoAssignCategory_hoe() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.DIAMOND_HOE)));
    }

    @Test
    void autoAssignCategory_bow() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.BOW)));
    }

    @Test
    void autoAssignCategory_crossbow() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.CROSSBOW)));
    }

    @Test
    void autoAssignCategory_trident() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.TRIDENT)));
    }

    @Test
    void autoAssignCategory_mace() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.MACE)));
    }

    @Test
    void autoAssignCategory_armor() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(item(Material.DIAMOND_CHESTPLATE)));
    }

    @Test
    void autoAssignCategory_food() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(item(Material.COOKED_BEEF)));
    }

    @Test
    void autoAssignCategory_potato() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(item(Material.POTATO)));
    }

    @Test
    void autoAssignCategory_carrot() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(item(Material.CARROT)));
    }

    @Test
    void autoAssignCategory_log() {
        assertEquals(Category.WOOD, scanner.autoAssignCategory(item(Material.OAK_LOG)));
    }

    @Test
    void autoAssignCategory_bamboo() {
        assertEquals(Category.WOOD, scanner.autoAssignCategory(item(Material.BAMBOO)));
    }

    @Test
    void autoAssignCategory_stick() {
        assertEquals(Category.WOOD, scanner.autoAssignCategory(item(Material.STICK)));
    }

    @Test
    void autoAssignCategory_stone() {
        assertEquals(Category.BUILDING, scanner.autoAssignCategory(item(Material.STONE)));
    }

    @Test
    void autoAssignCategory_copper() {
        assertEquals(Category.COPPER, scanner.autoAssignCategory(item(Material.COPPER_INGOT)));
    }

    @Test
    void autoAssignCategory_iron() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(item(Material.IRON_INGOT)));
    }

    @Test
    void autoAssignCategory_gold() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(item(Material.GOLD_INGOT)));
    }

    @Test
    void autoAssignCategory_diamond() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(item(Material.DIAMOND)));
    }

    @Test
    void autoAssignCategory_emerald() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(item(Material.EMERALD)));
    }

    @Test
    void autoAssignCategory_terracotta() {
        assertEquals(Category.COLORS, scanner.autoAssignCategory(item(Material.TERRACOTTA)));
    }

    @Test
    void autoAssignCategory_banner() {
        assertEquals(Category.DECORATION, scanner.autoAssignCategory(item(Material.WHITE_BANNER)));
    }

    @Test
    void autoAssignCategory_flower_pot() {
        assertEquals(Category.DECORATION, scanner.autoAssignCategory(item(Material.FLOWER_POT)));
    }

    @Test
    void autoAssignCategory_wool() {
        assertEquals(Category.COLORS, scanner.autoAssignCategory(item(Material.WHITE_WOOL)));
    }

    @Test
    void autoAssignCategory_redstone() {
        assertEquals(Category.REDSTONE, scanner.autoAssignCategory(item(Material.REDSTONE)));
    }

    @Test
    void autoAssignCategory_repeater() {
        assertEquals(Category.REDSTONE, scanner.autoAssignCategory(item(Material.REPEATER)));
    }

    @Test
    void autoAssignCategory_spawn_egg() {
        assertEquals(Category.SPAWNERS, scanner.autoAssignCategory(item(Material.CREEPER_SPAWN_EGG)));
    }

    @Test
    void autoAssignCategory_seed() {
        assertEquals(Category.FOOD_FARMING, scanner.autoAssignCategory(item(Material.WHEAT_SEEDS)));
    }

    @Test
    void autoAssignCategory_brick() {
        assertEquals(Category.BUILDING, scanner.autoAssignCategory(item(Material.BRICK)));
    }

    @Test
    void autoAssignCategory_deepslate() {
        assertEquals(Category.BUILDING, scanner.autoAssignCategory(item(Material.DEEPSLATE)));
    }

    @Test
    void autoAssignCategory_netheriteSword_is_tools() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.NETHERITE_SWORD)));
    }

    @Test
    void autoAssignCategory_diamondSword_is_tools() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.DIAMOND_SWORD)));
    }

    @Test
    void autoAssignCategory_goldenAxe_is_tools() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.GOLDEN_AXE)));
    }

    @Test
    void autoAssignCategory_ironPickaxe_is_tools() {
        assertEquals(Category.TOOLS_WEAPONS, scanner.autoAssignCategory(item(Material.IRON_PICKAXE)));
    }

    @Test
    void autoAssignCategory_unknown_returns_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(item(Material.PAPER)));
    }

    @Test
    void autoAssignCategory_null_returns_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(null));
    }

    @Test
    void autoAssignCategory_air_is_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(item(Material.AIR)));
    }

    @Test
    void autoAssignCategory_raw_iron() {
        assertEquals(Category.MINERALS_ORES, scanner.autoAssignCategory(item(Material.RAW_IRON)));
    }
}
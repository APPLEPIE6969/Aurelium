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
 * Tests the actual production code directly.
 * 
 * NOTE: autoAssignCategory tests with real ItemStack(Material) constructors
 * are not possible in unit tests because ItemStack(Material) triggers
 * Bukkit RegistryAccess static init. These are tested via the null path
 * and in integration tests.
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
    // autoAssignCategory - null safety
    // ======================================================

    @Test
    void autoAssignCategory_null_returns_custom() {
        assertEquals(Category.CUSTOM_ITEMS, scanner.autoAssignCategory(null));
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
}
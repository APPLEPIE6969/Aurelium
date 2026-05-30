package com.aureleconomy.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for UnifiedItemScanner utility methods that don't require
 * Paper API runtime initialization (RegistryAccess).
 */
class UnifiedItemScannerTest {

    private final UnifiedItemScanner scanner = new UnifiedItemScanner(null, null);

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
}
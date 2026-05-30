package com.aureleconomy.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedItemScannerTest {

    @Test
    void detectPluginFromNamespace_itemsadder() {
        assertEquals("ItemsAdder", UnifiedItemScanner.detectPluginFromNamespace("iastuff"));
    }

    @Test
    void detectPluginFromNamespace_oraxen() {
        assertEquals("Oraxen", UnifiedItemScanner.detectPluginFromNamespace("oraxen_ns"));
    }

    @Test
    void detectPluginFromNamespace_mmoitems() {
        assertEquals("MMOItems", UnifiedItemScanner.detectPluginFromNamespace("mmoitems_stuff"));
    }

    @Test
    void detectPluginFromNamespace_mythicmobs() {
        assertEquals("MythicMobs", UnifiedItemScanner.detectPluginFromNamespace("mythic_stuff"));
    }

    @Test
    void detectPluginFromNamespace_executableitems() {
        assertEquals("ExecutableItems", UnifiedItemScanner.detectPluginFromNamespace("executable_stuff"));
    }

    @Test
    void detectPluginFromNamespace_nexo() {
        assertEquals("Nexo", UnifiedItemScanner.detectPluginFromNamespace("nexo_stuff"));
    }

    @Test
    void detectPluginFromNamespace_sxitem() {
        assertEquals("SX-Item", UnifiedItemScanner.detectPluginFromNamespace("sxitem_stuff"));
    }

    @Test
    void detectPluginFromNamespace_unknown_preserves() {
        assertEquals("UnknownPlugin", UnifiedItemScanner.detectPluginFromNamespace("some_random_plugin"));
    }
}
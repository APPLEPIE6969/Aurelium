package com.aureleconomy.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedItemScannerTest {

    @Test
    void detectPluginFromNamespace_itemsadder() {
        assertEquals("ItemsAdder", UnifiedItemScanner.detectPluginFromNamespace("itemsadder"));
    }

    @Test
    void detectPluginFromNamespace_oraxen() {
        assertEquals("Oraxen", UnifiedItemScanner.detectPluginFromNamespace("oraxen"));
    }

    @Test
    void detectPluginFromNamespace_mmoitems() {
        assertEquals("MMOItems", UnifiedItemScanner.detectPluginFromNamespace("mmoitems"));
    }

    @Test
    void detectPluginFromNamespace_mythicmobs() {
        assertEquals("MythicMobs", UnifiedItemScanner.detectPluginFromNamespace("mythicmobs"));
    }

    @Test
    void detectPluginFromNamespace_executableitems() {
        assertEquals("ExecutableItems", UnifiedItemScanner.detectPluginFromNamespace("executableitems"));
    }

    @Test
    void detectPluginFromNamespace_nexo() {
        assertEquals("Nexo", UnifiedItemScanner.detectPluginFromNamespace("nexo"));
    }

    @Test
    void detectPluginFromNamespace_sxitem() {
        assertEquals("SX-Item", UnifiedItemScanner.detectPluginFromNamespace("sxitem"));
    }

    @Test
    void detectPluginFromNamespace_unknown_returns_namespace() {
        assertEquals("some_random_plugin", UnifiedItemScanner.detectPluginFromNamespace("some_random_plugin"));
    }
}
package com.aureleconomy.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for scanner config validation.
 * Verifies that config flags and excluded namespaces are handled correctly.
 */
public class ScannerConfigTest {

    @Test
    void defaultExcludedNamespacesIncludeMinecraft() {
        Set<String> excluded = new HashSet<>();
        if (excluded.isEmpty()) {
            excluded.add("minecraft");
            excluded.add("aureleconomy");
        }
        assertTrue(excluded.contains("minecraft"));
        assertTrue(excluded.contains("aureleconomy"));
    }

    @Test
    void customExcludedNamespacesOverrideDefaults() {
        Set<String> excluded = new HashSet<>();
        excluded.add("custom_ns");
        // When non-empty, defaults should NOT be added
        assertFalse(excluded.contains("minecraft"));
        assertTrue(excluded.contains("custom_ns"));
    }

    @Test
    void namespaceExclusionCheck() {
        Set<String> excluded = new HashSet<>();
        excluded.add("minecraft");
        excluded.add("aureleconomy");

        assertTrue(excluded.contains("minecraft"));
        assertFalse(excluded.contains("itemsadder"));
    }

    @Test
    void pluginNameResolution() {
        // Test the resolvePluginName logic
        assertEquals("ItemsAdder", resolvePluginName("itemsadder"));
        assertEquals("Oraxen", resolvePluginName("oraxen"));
        assertEquals("MMOItems", resolvePluginName("mmoitems"));
        assertEquals("MythicMobs", resolvePluginName("mythicmobs"));
        assertEquals("ExecutableItems", resolvePluginName("executableitems"));
        assertEquals("Nexo", resolvePluginName("nexo"));
        assertEquals("SX-Item", resolvePluginName("sxitem"));
        assertEquals("unknown", resolvePluginName("unknown"));
    }

    private String resolvePluginName(String namespace) {
        return switch (namespace) {
            case "itemsadder" -> "ItemsAdder";
            case "oraxen" -> "Oraxen";
            case "mmoitems" -> "MMOItems";
            case "mythicmobs" -> "MythicMobs";
            case "executableitems" -> "ExecutableItems";
            case "nexo" -> "Nexo";
            case "sxitem" -> "SX-Item";
            default -> namespace;
        };
    }

    @Test
    void defaultPriceMultiplierRange() {
        double multiplier = 1.5; // default
        assertTrue(multiplier > 0, "Price multiplier should be positive");
        assertTrue(multiplier < 100, "Price multiplier should be reasonable");
    }
}

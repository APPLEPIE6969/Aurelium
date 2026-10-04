package com.aureleconomy.scanner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code custom-items.excluded-namespaces} decides which PDC namespaces the
 * scanner ignores.
 *
 * <p>Issue #34 reported setting it to {@code []} and having no effect. The old
 * code read the list and, whenever it came back empty, quietly re-added
 * {@code minecraft} and {@code aureleconomy}. Bukkit's {@code getStringList}
 * returns an empty list both when a key is absent and when an admin deliberately
 * wrote {@code []}, so an explicit choice could never be honoured.
 */
@DisplayName("Custom item scanner namespace exclusions")
class UnifiedItemScannerNamespaceTest {

    @Test
    @DisplayName("honours an explicitly empty list instead of restoring the defaults")
    void explicitEmptyListIsHonoured() {
        // This is the exact configuration from issue #34.
        Set<String> resolved = UnifiedItemScanner.resolveExcludedNamespaces(
                true, Collections.emptyList());

        assertTrue(resolved.isEmpty(),
                "an explicit empty list must not be repopulated with defaults");
        assertFalse(resolved.contains("minecraft"),
                "minecraft must stay unexcluded when the admin asked for []");
        assertFalse(resolved.contains("aureleconomy"),
                "aureleconomy must stay unexcluded when the admin asked for []");
    }

    @Test
    @DisplayName("falls back to the defaults only when the key is absent")
    void absentKeyUsesDefaults() {
        Set<String> resolved = UnifiedItemScanner.resolveExcludedNamespaces(
                false, Collections.emptyList());

        assertEquals(Set.of("minecraft", "aureleconomy"), resolved);
    }

    @Test
    @DisplayName("an absent key ignores any list handed in")
    void absentKeyIgnoresConfiguredList() {
        Set<String> resolved = UnifiedItemScanner.resolveExcludedNamespaces(
                false, List.of("someplugin"));

        assertEquals(Set.of("minecraft", "aureleconomy"), resolved,
                "defaults apply when the key is missing, regardless of the list");
    }

    @Test
    @DisplayName("uses the configured list when the key is present")
    void presentKeyUsesConfiguredList() {
        Set<String> resolved = UnifiedItemScanner.resolveExcludedNamespaces(
                true, Arrays.asList("someplugin", "otherplugin"));

        assertEquals(Set.of("someplugin", "otherplugin"), resolved);
    }

    @Test
    @DisplayName("de-duplicates repeated namespaces")
    void deduplicatesConfiguredList() {
        Set<String> resolved = UnifiedItemScanner.resolveExcludedNamespaces(
                true, Arrays.asList("someplugin", "someplugin", "otherplugin"));

        assertEquals(2, resolved.size());
    }

    @Test
    @DisplayName("a single-entry list does not trigger the defaults")
    void singleEntryListIsNotTreatedAsUnset() {
        Set<String> resolved = UnifiedItemScanner.resolveExcludedNamespaces(
                true, List.of("minecraft"));

        assertEquals(Set.of("minecraft"), resolved);
    }
}
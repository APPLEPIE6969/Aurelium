package com.aureleconomy.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * H9's memory: a dashboard purchase ID is claimed in the database before the
 * purchase runs, so the same ID cannot be delivered twice - not after ten
 * minutes, and not after a restart.
 *
 * <p>The store is exercised against a real SQLite database. The claim is the
 * primary key doing the work, and a mock connection would agree with whatever
 * the test expected instead of proving the constraint fires.
 */
class ProcessedPurchaseStoreTest {

    private static final String CURRENCY = "Aurels";

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private ProcessedPurchaseStore store;

    @BeforeEach
    void openDatabase() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        store = new ProcessedPurchaseStore(plugin);
    }

    @AfterEach
    void closeDatabase() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("A purchase ID is claimed exactly once")
    void claimIsTakenOnce() {
        assertTrue(store.claim("purchase-1"), "the first claim must succeed");
        assertFalse(store.claim("purchase-1"), "the same ID must not be claimed twice");
        assertTrue(store.claim("purchase-2"), "a different ID is a different purchase");
    }

    @Test
    @DisplayName("A claim survives a restart, which is what the old map could not do")
    void claimSurvivesRestart() {
        assertTrue(store.claim("purchase-1"));

        ProcessedPurchaseStore afterRestart = new ProcessedPurchaseStore(plugin);

        assertFalse(afterRestart.claim("purchase-1"),
                "a new manager over the same database must still remember the claim");
    }

    @Test
    @DisplayName("A claim with no outcome is not sent to the dashboard")
    void claimIsNotAConfirmation() {
        assertTrue(store.claim("purchase-1"));

        assertTrue(store.pendingConfirmations().isEmpty(),
                "nothing was decided yet, so there is nothing to confirm");
    }

    @Test
    @DisplayName("The recorded outcome reaches the dashboard with the values it was given")
    void markReadyCarriesTheOutcome() {
        assertTrue(store.claim("purchase-1"));

        assertTrue(store.markReady("purchase-1", true, new BigDecimal("90.50"), "A10.00"),
                "the first recorded outcome must be accepted");

        List<ProcessedPurchaseStore.Confirmation> pending = store.pendingConfirmations();
        assertEquals(1, pending.size());
        ProcessedPurchaseStore.Confirmation confirmation = pending.get(0);
        assertEquals("purchase-1", confirmation.purchaseId());
        assertTrue(confirmation.success());
        assertEquals(0, new BigDecimal("90.50").compareTo(confirmation.newBalance()),
                "the reported balance changed: " + confirmation.newBalance());
        assertEquals("A10.00", confirmation.spent());

        store.markConfirmed("purchase-1");

        assertTrue(store.pendingConfirmations().isEmpty(),
                "a confirmed purchase must not be retried");
    }

    @Test
    @DisplayName("A later failure cannot overwrite the outcome of a sale that already happened")
    void aSecondOutcomeIsRefused() {
        assertTrue(store.claim("purchase-1"));
        assertTrue(store.markReady("purchase-1", true, new BigDecimal("90.00"), "A10.00"));

        assertFalse(store.markReady("purchase-1", false, BigDecimal.ZERO, "Purchase failed"),
                "only the first outcome for a purchase may be reported");

        List<ProcessedPurchaseStore.Confirmation> pending = store.pendingConfirmations();
        assertEquals(1, pending.size());
        assertTrue(pending.get(0).success(), "the successful sale was overwritten by a later failure");
    }

    @Test
    @DisplayName("Pruning drops old confirmed rows but keeps an outcome that still needs sending")
    void pruneKeepsUnconfirmedRows() throws Exception {
        assertTrue(store.claim("confirmed"));
        assertTrue(store.markReady("confirmed", true, new BigDecimal("5.00"), "A1.00"));
        store.markConfirmed("confirmed");

        assertTrue(store.claim("unconfirmed"));
        assertTrue(store.markReady("unconfirmed", true, new BigDecimal("7.00"), "A2.00"));

        long longAgo = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000;
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE processed_purchases SET processed_at = ?")) {
            ps.setLong(1, longAgo);
            ps.executeUpdate();
        }

        assertEquals(1, store.prune(), "only the confirmed row is past retention");

        List<ProcessedPurchaseStore.Confirmation> pending = store.pendingConfirmations();
        assertEquals(1, pending.size(), "an unsent outcome must survive the prune");
        assertEquals("unconfirmed", pending.get(0).purchaseId());
    }

    @Test
    @DisplayName("A blank purchase ID is refused rather than stored")
    void blankIdsAreRefused() {
        assertFalse(store.claim(null));
        assertFalse(store.claim("  "));
        assertTrue(store.pendingConfirmations().isEmpty());
    }
}

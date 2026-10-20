package com.aureleconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * F1: every balance mutation leaves exactly one ledger row with the right sign,
 * counterparty and resulting balance, against a real SQLite database.
 *
 * <p>The rows are read back through a second connection, so the assertion is on
 * what is actually stored rather than on what the service was told.
 *
 * <p>{@code Bukkit.isPrimaryThread()} is stubbed false, which makes
 * {@code scheduleAsyncWrite} run inline - the admin set test depends on that.
 */
class EconomyLedgerTest {

    private static final String CURRENCY = "Aurels";

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private LedgerService ledger;
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;

    private final UUID aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID bobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private OfflinePlayer alice;
    private OfflinePlayer bob;

    @BeforeEach
    void openBank() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        config.set("economy.starting-balance", 0.0d);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        ledger = new LedgerService(plugin);
        when(plugin.getLedgerService()).thenReturn(ledger);

        economy = new EconomyManager(plugin);

        bukkit = mockStatic(Bukkit.class);
        when(Bukkit.isPrimaryThread()).thenReturn(false);

        alice = mockPlayer(aliceId, "Alice");
        bob = mockPlayer(bobId, "Bob");

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());
    }

    private static OfflinePlayer mockPlayer(UUID uuid, String name) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @AfterEach
    void closeBank() throws Exception {
        if (observer != null) {
            observer.close();
        }
        if (bukkit != null) {
            bukkit.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    private void seed(UUID uuid, String amount) throws Exception {
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, CURRENCY);
            ps.setBigDecimal(3, new BigDecimal(amount));
            ps.executeUpdate();
        }
    }

    private int rowCount() throws Exception {
        try (Statement stmt = observer.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM transaction_ledger")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private List<LedgerService.Row> allRows() throws Exception {
        List<LedgerService.Row> rows = new java.util.ArrayList<>();
        try (Statement stmt = observer.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, ts, type, counterparty_uuid, item_key, amount, currency,"
                             + " balance_after, ref_id FROM transaction_ledger ORDER BY id")) {
            while (rs.next()) {
                rows.add(new LedgerService.Row(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7),
                        rs.getBigDecimal(8), rs.getString(9)));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("a deposit records one positive row with the balance it produced")
    void depositIsRecorded() throws Exception {
        seed(aliceId, "100.00");

        assertTrue(economy.deposit(alice, new BigDecimal("25.00"), CURRENCY,
                LedgerReason.OFFLINE_EARNING, "earn-1", "IRON_INGOT"));
        assertEquals(1, ledger.flush(), "one mutation must produce one row");

        List<LedgerService.Row> rows = allRows();
        assertEquals(1, rows.size());
        LedgerService.Row row = rows.get(0);
        assertEquals("OFFLINE_EARNING", row.type());
        assertEquals(new BigDecimal("25.00"), row.amount().setScale(2));
        assertEquals(new BigDecimal("125.00"), row.balanceAfter().setScale(2),
                "the row must carry the balance the mutation produced");
        assertEquals("earn-1", row.refId());
        assertEquals("IRON_INGOT", row.itemKey());
        assertNull(row.counterparty(), "a deposit has no counterparty");
    }

    @Test
    @DisplayName("a refused withdrawal writes no row")
    void refusedWithdrawalIsNotRecorded() throws Exception {
        seed(aliceId, "10.00");

        assertTrue(!economy.withdraw(alice, new BigDecimal("50.00"), CURRENCY),
                "the withdrawal must be refused for lack of funds");
        assertEquals(0, ledger.flush(), "nothing moved, so nothing may be recorded");
        assertEquals(0, rowCount());
    }

    @Test
    @DisplayName("a transfer records both legs, signed and pointing at each other")
    void transferRecordsBothSides() throws Exception {
        seed(aliceId, "300.00");
        seed(bobId, "50.00");

        assertTrue(economy.transfer(alice, bob, new BigDecimal("25.00"), CURRENCY, LedgerReason.PAY));
        assertEquals(2, ledger.flush(), "a transfer moves money twice, once per side");

        List<LedgerService.Row> rows = allRows();
        assertEquals(2, rows.size());

        LedgerService.Row paid = rows.get(0);
        assertEquals("PAY", paid.type());
        assertEquals(new BigDecimal("-25.00"), paid.amount().setScale(2), "the payer's row is negative");
        assertEquals(bobId.toString(), paid.counterparty());
        assertEquals(new BigDecimal("275.00"), paid.balanceAfter().setScale(2));

        LedgerService.Row received = rows.get(1);
        assertEquals("PAY", received.type());
        assertEquals(new BigDecimal("25.00"), received.amount().setScale(2), "the payee's row is positive");
        assertEquals(aliceId.toString(), received.counterparty());
        assertEquals(new BigDecimal("75.00"), received.balanceAfter().setScale(2));
    }

    @Test
    @DisplayName("an admin set records the delta it caused, not the new balance")
    void adminSetRecordsTheDelta() throws Exception {
        seed(aliceId, "100.00");

        economy.setBalance(alice, new BigDecimal("40.00"), CURRENCY);
        assertEquals(1, ledger.flush());

        LedgerService.Row row = allRows().get(0);
        assertEquals("ADMIN_SET", row.type());
        assertEquals(new BigDecimal("-60.00"), row.amount().setScale(2),
                "the row must show what the set took away, so summing rows still totals money");
        assertEquals(new BigDecimal("40.00"), row.balanceAfter().setScale(2));
    }

    @Test
    @DisplayName("a first balance records the starting balance as money created")
    void startingBalanceIsRecorded() throws Exception {
        // getBalance on an account with no row creates the starting balance (C4).
        BigDecimal balance = economy.getBalance(alice, CURRENCY);

        assertEquals(0.0, balance.doubleValue(), 0.0001);
        assertEquals(1, ledger.flush());

        LedgerService.Row row = allRows().get(0);
        assertEquals("STARTING_BALANCE", row.type());
        assertNotNull(row.balanceAfter());
    }

    @Test
    @DisplayName("history reads back newest first, one page at a time")
    void historyReadsNewestFirst() throws Exception {
        seed(aliceId, "1000.00");
        for (int i = 1; i <= 12; i++) {
            economy.deposit(alice, new BigDecimal(i + ".00"), CURRENCY,
                    LedgerReason.DEPOSIT, "dep-" + i, null);
        }
        assertEquals(12, ledger.flush());

        List<LedgerService.Row> first = ledger.history(aliceId, 1);
        assertEquals(LedgerService.PAGE_SIZE, first.size());
        assertEquals("dep-12", first.get(0).refId(), "history must start at the newest row");
        assertEquals("dep-3", first.get(first.size() - 1).refId());

        List<LedgerService.Row> second = ledger.history(aliceId, 2);
        assertEquals(2, second.size());
        assertEquals("dep-2", second.get(0).refId());
        assertEquals("dep-1", second.get(1).refId());
    }
}

package com.aureleconomy.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.LedgerService;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * F1: {@code /eco history <player> [page]} - the operator's way to see what the
 * ledger recorded.
 *
 * <p>The scheduler is stubbed to run tasks inline, so the async read and the hop
 * back to the main thread both complete before the assertions.
 */
class EconomyHistoryTest {

    private static final String CURRENCY = "Aurels";

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private LedgerService ledger;
    private EconomyCommand command;
    private MockedStatic<Bukkit> bukkit;
    private final List<Component> messages = new ArrayList<>();

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

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        ledger = new LedgerService(plugin);
        when(plugin.getLedgerService()).thenReturn(ledger);

        command = new EconomyCommand(plugin);

        alice = mockPlayer(aliceId, "Alice");
        bob = mockPlayer(bobId, "Bob");

        bukkit = mockStatic(Bukkit.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(Bukkit.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(any(), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        });
        when(scheduler.runTask(any(), any(Runnable.class))).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        });
        when(Bukkit.getOfflinePlayer("Alice")).thenReturn(alice);
        when(Bukkit.getOfflinePlayer(bobId)).thenReturn(bob);
    }

    private static OfflinePlayer mockPlayer(UUID uuid, String name) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        return player;
    }

    @AfterEach
    void closeBank() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    private void record(long ts, String type, BigDecimal amount, String currency,
            BigDecimal after, UUID counterparty, String refId) {
        ledger.record(new LedgerService.Entry(ts, aliceId, counterparty, type, "IRON_INGOT",
                amount, currency, after, refId, null));
    }

    private List<String> lines() {
        List<String> text = new ArrayList<>();
        for (Component message : messages) {
            text.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
        return text;
    }

    private CommandSender adminSender() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.hasPermission("aureleconomy.admin")).thenReturn(true);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(sender).sendMessage(any(Component.class));
        return sender;
    }

    @Test
    @DisplayName("history prints one line per row, newest first, with the amount and who it was with")
    void historyPrintsRows() {
        record(1_700_000_000_000L, "BID", new BigDecimal("-25.00"), CURRENCY,
                new BigDecimal("75.00"), null, "42");
        record(1_700_000_001_000L, "PAY", new BigDecimal("10.00"), CURRENCY,
                new BigDecimal("85.00"), bobId, null);
        assertEquals(2, ledger.flush());

        command.onCommand(adminSender(), mock(Command.class), "eco",
                new String[]{"history", "Alice"});

        List<String> lines = lines();
        assertEquals("Processing...", lines.get(0));
        assertEquals("Alice - money history, page 1", lines.get(1));
        assertEquals(4, lines.size(), "two rows, two lines: " + lines);

        assertTrue(lines.get(2).contains("PAY"), lines.get(2));
        assertTrue(lines.get(2).contains("+10.00"), lines.get(2));
        assertTrue(lines.get(2).contains("with Bob"), lines.get(2));

        assertTrue(lines.get(3).contains("BID"), lines.get(3));
        assertTrue(lines.get(3).contains("-25.00"), lines.get(3));
        assertTrue(lines.get(3).contains("#42"), lines.get(3));
    }

    @Test
    @DisplayName("page two shows the rows the first page pushed off")
    void historyPaginates() {
        for (int i = 1; i <= 12; i++) {
            record(1_700_000_000_000L + i, "DEPOSIT", new BigDecimal("1.00"), CURRENCY,
                    new BigDecimal(i + ".00"), null, "dep-" + i);
        }
        assertEquals(12, ledger.flush());

        command.onCommand(adminSender(), mock(Command.class), "eco",
                new String[]{"history", "Alice", "2"});

        List<String> lines = lines();
        assertEquals("Alice - money history, page 2", lines.get(1));
        assertEquals(4, lines.size(), "processing, header, two rows: " + lines);
        assertTrue(lines.get(2).contains("#dep-2"), lines.toString());
    }

    @Test
    @DisplayName("a player with no history gets a plain answer, not an empty page")
    void emptyHistorySaysSo() {
        command.onCommand(adminSender(), mock(Command.class), "eco",
                new String[]{"history", "Alice", "3"});

        List<String> lines = lines();
        assertTrue(lines.get(lines.size() - 1).contains("No ledger rows for Alice on page 3"),
                lines.toString());
    }

    @Test
    @DisplayName("history is admin-only, and needs a player name")
    void historyIsGuarded() {
        CommandSender guest = mock(CommandSender.class);
        when(guest.hasPermission("aureleconomy.admin")).thenReturn(false);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(guest).sendMessage(any(Component.class));

        command.onCommand(guest, mock(Command.class), "eco", new String[]{"history", "Alice"});
        assertEquals(List.of("No permission."), lines());

        messages.clear();
        command.onCommand(adminSender(), mock(Command.class), "eco", new String[]{"history"});
        assertEquals(List.of("Usage: /eco history <player> [page]"), lines());
        assertFalse(lines().get(0).contains("Processing"));
    }

    /**
     * The history branch reads {@code args[0]} before the old length check, so a
     * bare {@code /eco} threw {@code ArrayIndexOutOfBoundsException} and the outer
     * handler reported it as an internal error. The server log audit in CI
     * treats any such error as a failure, and so does this test.
     */
    @Test
    @DisplayName("a bare /eco is answered with the usage line, not an internal error")
    void bareEcoIsAnswered() {
        command.onCommand(adminSender(), mock(Command.class), "eco", new String[0]);

        List<String> lines = lines();
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("Usage: /eco"), lines.toString());
    }
}

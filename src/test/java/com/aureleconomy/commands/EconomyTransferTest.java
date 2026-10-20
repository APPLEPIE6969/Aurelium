package com.aureleconomy.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * C3: {@code /pay} moves money or moves nothing.
 *
 * <p>The command used to read the payer's balance, then call {@code withdraw()}
 * without looking at the result and {@code deposit()} unconditionally. When the
 * withdraw silently matched no row (a balance that changed between the read and
 * the write, or a database fault) the deposit still ran and minted the amount.
 *
 * <p>It now makes one {@code transfer()} call and stops when it returns false.
 * These tests assert the money outcome through a real SQLite database, and assert
 * that the two-step withdraw/deposit is not reached on any path.
 *
 * <p>The confirm-purchase half of this fix is C1's: {@code ConfirmPurchaseGUI}
 * no longer withdraws before {@code bid()}, and
 * {@code AuctionManagerMoneyTest.buyNowChargesTheBuyerOnce} covers it.
 */
class EconomyTransferTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal START = new BigDecimal("500.00");
    private static final BigDecimal TARGET_START = new BigDecimal("250.00");
    private static final BigDecimal POOR = new BigDecimal("10.00");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private EconomyCommand command;
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private final Map<String, OfflinePlayer> offlinePlayers = new ConcurrentHashMap<>();
    private final List<Component> messages = new ArrayList<>();

    private UUID payerId;
    private UUID targetId;
    private Player payer;

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
        when(plugin.getComponentLogger()).thenReturn(mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class));
        when(plugin.isEnabled()).thenReturn(true);

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = spy(new EconomyManager(plugin));
        when(plugin.getEconomyManager()).thenReturn(economy);

        bukkit = mockStatic(Bukkit.class);
        stubBukkit();

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        payerId = new UUID(0x9a11e2L, 0x21L);
        targetId = new UUID(0xb1dde2L, 0x22L);

        seedBalance(payerId, START);
        seedBalance(targetId, TARGET_START);

        payer = mock(Player.class);
        when(payer.getUniqueId()).thenReturn(payerId);
        when(payer.getName()).thenReturn("payer");
        when(payer.hasPermission(anyString())).thenReturn(true);
        doAnswer(call -> {
            messages.add(call.getArgument(0));
            return null;
        }).when(payer).sendMessage(any(Component.class));

        offlinePlayer("target", targetId);
        offlinePlayer("payer", payerId);

        command = new EconomyCommand(plugin);
    }

    @AfterEach
    void closeBank() throws SQLException {
        if (bukkit != null) {
            bukkit.close();
        }
        if (observer != null) {
            observer.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    // -------------------- the required cases --------------------

    @Test
    @DisplayName("/pay moves exactly the amount and mints nothing")
    void payMovesExactlyTheAmount() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        runPay("target", "125.00");

        assertAmount("the payer's balance", START.subtract(new BigDecimal("125.00")), balanceOf(payerId));
        assertAmount("the target's balance", TARGET_START.add(new BigDecimal("125.00")), balanceOf(targetId));
        // A payment moves money; it does not create it. The old path could mint the
        // amount whenever the withdraw silently failed.
        MoneyInvariants.assertMoneyUnchanged(observer, "a /pay payment", before);

        assertEquals("You paid target 125.00A", lastMessage());
        verify(economy, never()).withdraw(any(OfflinePlayer.class), any(BigDecimal.class), anyString());
        verify(economy, never()).deposit(any(OfflinePlayer.class), any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("/pay with too little money changes no balance and says so")
    void payRefusedWhenThereIsNotEnough() throws Exception {
        seedBalance(payerId, POOR);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        runPay("target", "100.00");

        assertAmount("the payer's balance", POOR, balanceOf(payerId));
        assertAmount("the target's balance", TARGET_START, balanceOf(targetId));
        MoneyInvariants.assertMoneyUnchanged(observer, "a refused /pay payment", before);
        assertEquals("Insufficient funds.", lastMessage());
    }

    @Test
    @DisplayName("/pay stops at a failed transfer instead of withdrawing and depositing")
    void payNeverWithdrawsThenDeposits() throws Exception {
        // The divergence the old code could not survive: the balance check says yes
        // but the write is refused. Forcing the one call the command now makes is
        // the smallest way to reach that state.
        // /pay now names its ledger reason, so the stub has to match the overload
        // the command actually calls. A stub on the old signature would no longer
        // intercept anything and the test would pass for the wrong reason.
        doReturn(false).when(economy).transfer(any(OfflinePlayer.class), any(OfflinePlayer.class),
                any(BigDecimal.class), anyString(), any());
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        runPay("target", "100.00");

        assertAmount("the payer's balance", START, balanceOf(payerId));
        assertAmount("the target's balance", TARGET_START, balanceOf(targetId));
        MoneyInvariants.assertMoneyUnchanged(observer, "a transfer that was refused", before);
        verify(economy, never()).withdraw(any(OfflinePlayer.class), any(BigDecimal.class), anyString());
        verify(economy, never()).deposit(any(OfflinePlayer.class), any(BigDecimal.class), anyString());
        assertEquals("Insufficient funds.", lastMessage());
    }

    @Test
    @DisplayName("/pay to yourself is refused")
    void payToYourselfIsRefused() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        runPay("payer", "10.00");

        assertAmount("the payer's balance", START, balanceOf(payerId));
        MoneyInvariants.assertMoneyUnchanged(observer, "paying yourself", before);
        assertEquals("You cannot pay yourself.", lastMessage());
        verify(economy, never()).transfer(any(OfflinePlayer.class), any(OfflinePlayer.class),
                any(BigDecimal.class), anyString(), any());
    }

    // -------------------- helpers --------------------

    private void runPay(String targetName, String amount) {
        command.onCommand(payer, mock(Command.class), "pay", new String[] { targetName, amount });
    }

    /**
     * The scheduler runs its work inline: {@code /pay} looks the target up
     * asynchronously and then does the money work on the main thread. A scheduler
     * that never ran them would leave every assertion reading pre-flight state.
     */
    private void stubBukkit() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(scheduler).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));

        when(Bukkit.getScheduler()).thenReturn(scheduler);
        when(Bukkit.getOfflinePlayer(anyString())).thenAnswer(call -> {
            String name = call.getArgument(0);
            OfflinePlayer known = offlinePlayers.get(name);
            return known != null ? known : offlinePlayer(name, null);
        });
    }

    private OfflinePlayer offlinePlayer(String name, UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        UUID resolved = id != null ? id : new UUID(0xdeadL, Math.abs(name.hashCode()));
        when(player.getUniqueId()).thenReturn(resolved);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(false);
        offlinePlayers.put(name, player);
        return player;
    }

    private void seedBalance(UUID account, BigDecimal amount) throws SQLException {
        try (PreparedStatement delete = observer.prepareStatement(
                "DELETE FROM player_balances WHERE uuid = ? AND currency = ?")) {
            delete.setString(1, account.toString());
            delete.setString(2, CURRENCY);
            delete.executeUpdate();
        }
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        }
    }

    private BigDecimal balanceOf(UUID account) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("balance") : null;
            }
        }
    }

    private String lastMessage() {
        assertTrue(!messages.isEmpty(), "the command sent no message");
        return PlainTextComponentSerializer.plainText().serialize(messages.get(messages.size() - 1));
    }

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

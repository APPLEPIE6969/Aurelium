package com.aureleconomy.listeners;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.utils.OfflineEarnings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a player is told they earned while offline (M14).
 *
 * <p>{@code offline_earnings} has always had a {@code currency} column, but no
 * write named it and the join message formatted every amount with the default
 * currency's symbol. A player paid in Dollars was told the amount in Aurels.
 */
class OfflineEarningCurrencyTest {

    @TempDir
    Path tempDir;

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private DatabaseManager manager;
    private MockedStatic<Bukkit> bukkit;
    private Player player;
    private AurelEconomy plugin;

    @BeforeEach
    void setUp() {
        File dataFolder = tempDir.resolve("listener").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", "Aurels");
        config.set("economy.currencies.Aurels.symbol", "\u20B3");
        config.set("economy.currencies.Dollars.symbol", "$");
        // A symbol that belongs to neither currency: a NULL currency that falls
        // through to this instead of the default currency is visible.
        config.set("economy.currency-symbol", "!");

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "the test database must initialize");
        when(plugin.getDatabaseManager()).thenReturn(manager);
        when(plugin.getEconomyManager()).thenReturn(new EconomyManager(plugin));

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(), any(Runnable.class))).thenAnswer(call -> {
            Runnable task = call.getArgument(1);
            task.run();
            return mock(BukkitTask.class);
        });
        bukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(PLAYER);
    }

    @AfterEach
    void tearDown() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    private void seedEarning(String currency, String amount) throws Exception {
        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO offline_earnings (uuid, amount, currency, item_display, timestamp)"
                             + " VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, PLAYER.toString());
            ps.setBigDecimal(2, new BigDecimal(amount));
            ps.setString(3, currency);
            ps.setString(4, "Diamond (x2)");
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private String joinMessages() {
        PlayerJoinEvent event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        new JoinListener(plugin).onJoin(event);

        ArgumentCaptor<Component> messages = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeastOnce()).sendMessage(messages.capture());
        return messages.getAllValues().stream()
                .map(this::plain)
                .collect(Collectors.joining());
    }

    @Test
    @DisplayName("an earning paid in Dollars is announced with the Dollar symbol")
    void joinMessageUsesTheEarningsCurrency() throws Exception {
        seedEarning("Dollars", "25.00");

        String joined = joinMessages();

        assertTrue(joined.contains("$25.00"),
                "the join message must show the currency the earning was paid in, got: " + joined);
        assertFalse(joined.contains("\u20B325.00"),
                "the default currency's symbol is wrong for a Dollar earning: " + joined);
    }

    @Test
    @DisplayName("a row written before the fix falls back to the default currency")
    void legacyNullCurrencyUsesTheDefault() throws Exception {
        seedEarning(null, "25.00");

        String joined = joinMessages();

        assertTrue(joined.contains("\u20B325.00"),
                "a NULL currency means the default currency, not the generic fallback symbol: " + joined);
        assertFalse(joined.contains("!25.00"),
                "the generic symbol is not a currency: " + joined);
    }

    @Test
    @DisplayName("recording an earning writes the currency with it")
    void recordWritesTheCurrency() throws Exception {
        OfflineEarnings.record(plugin, PLAYER, new BigDecimal("25.00"), "Dollars", "Diamond (x2)");

        try (Connection conn = manager.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT currency FROM offline_earnings WHERE uuid = ?")) {
            ps.setString(1, PLAYER.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the earning was not recorded");
                assertEquals("Dollars", rs.getString("currency"),
                        "the row has to carry the currency, or every join falls back to the default");
            }
        }
    }

    private String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}

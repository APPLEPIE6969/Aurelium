package com.aureleconomy.economy;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EconomyManagerTest {

    @Mock
    private AurelEconomy plugin;

    @Mock
    private FileConfiguration config;

    @Mock
    private DatabaseManager databaseManager;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private BukkitScheduler scheduler;

    @Mock
    private OfflinePlayer player;

    private EconomyManager economyManager;
    private MockedStatic<Bukkit> mockedBukkit;

    private final UUID playerUUID = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        mockedBukkit = mockStatic(Bukkit.class);
        mockedBukkit.when(Bukkit::getScheduler).thenReturn(scheduler);

        lenient().when(plugin.getConfig()).thenReturn(config);

        // Don't execute the runnable instantly, we want to test the optimistic updates BEFORE the DB task overrides them!
        lenient().when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            return mock(BukkitTask.class);
        });

        // Basic plugin and DB setup
        lenient().when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        lenient().when(databaseManager.getConnection()).thenReturn(connection);
        lenient().when(plugin.getComponentLogger()).thenReturn(mock(net.kyori.adventure.text.logger.slf4j.ComponentLogger.class));
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        lenient().when(preparedStatement.executeQuery()).thenReturn(resultSet);
        // Important: mock executeUpdate to return 1 (success) to avoid exceptions in tests where it is expected to succeed.
        lenient().when(preparedStatement.executeUpdate()).thenReturn(1);

        economyManager = new EconomyManager(plugin);

        lenient().when(player.getUniqueId()).thenReturn(playerUUID);
        lenient().when(player.getName()).thenReturn("TestPlayer");

        // Setup default config for most tests
        lenient().when(config.getString("economy.default-currency", "Aurels")).thenReturn("Aurels");
    }

    @AfterEach
    void tearDown() {
        if (mockedBukkit != null) {
            mockedBukkit.close();
        }
    }

    @Test
    void testGetDefaultCurrency() {
        // Specifically override for this test
        when(config.getString("economy.default-currency", "Aurels")).thenReturn("Credits");
        assertEquals("Credits", economyManager.getDefaultCurrency());
    }

    @Test
    @DisplayName("Should return cached balance if available")
    void testGetBalance_Cached() throws SQLException {
        // Setup cache by directly setting balance
        economyManager.setBalance(player, new BigDecimal("150.00"));

        // Re-call getBalance to ensure it uses cache
        BigDecimal balance = economyManager.getBalance(player);

        assertEquals(new BigDecimal("150.00"), balance);
    }

    @Test
    @DisplayName("Should load balance from database if not cached")
    void testGetBalance_FromDatabase() throws SQLException {
        // Mock database returning 250.00
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBigDecimal("balance")).thenReturn(new BigDecimal("250.00"));

        BigDecimal balance = economyManager.getBalance(player);

        assertEquals(new BigDecimal("250.00").setScale(2, java.math.RoundingMode.HALF_EVEN), balance);
        verify(preparedStatement, atLeast(1)).executeQuery();
    }

    @Test
    @DisplayName("Should return default starting balance if not in database")
    void testGetBalance_DefaultBalance() throws SQLException {
        // Mock database returning empty
        when(resultSet.next()).thenReturn(false);
        lenient().when(config.getDouble(eq("economy.currencies.Aurels.starting-balance"), anyDouble())).thenReturn(50.0);

        BigDecimal balance = economyManager.getBalance(player);

        assertEquals(new BigDecimal("50.00").setScale(2, java.math.RoundingMode.HALF_EVEN), balance);
        verify(preparedStatement, atLeast(1)).executeQuery();
    }

    @Test
    @DisplayName("Should deposit amount to database and cache")
    void testDeposit() throws SQLException {
        // Mock DB: Not in database yet
        when(resultSet.next()).thenReturn(false);
        lenient().when(config.getDouble(eq("economy.currencies.Aurels.starting-balance"), anyDouble())).thenReturn(100.0);

        // Load initial balance to cache
        economyManager.getBalance(player);

        economyManager.deposit(player, new BigDecimal("50.50"));

        // Verify cache is updated
        BigDecimal updatedBalance = economyManager.getBalance(player);
        assertEquals(new BigDecimal("150.50").setScale(2, java.math.RoundingMode.HALF_EVEN), updatedBalance);
    }

    @Test
    @DisplayName("Should ignore negative deposits")
    void testDeposit_NegativeAmount() throws SQLException {
        economyManager.deposit(player, new BigDecimal("-10.00"));
        verify(preparedStatement, never()).executeUpdate();
    }

    @Test
    @DisplayName("Should withdraw amount from database and cache")
    void testWithdraw() throws SQLException {
        // Set initial balance
        economyManager.setBalance(player, new BigDecimal("200.00"));

        // Force load balance to cache
        economyManager.getBalance(player);

        economyManager.withdraw(player, new BigDecimal("50.00"));

        // Verify cache is updated
        BigDecimal updatedBalance = economyManager.getBalance(player);
        assertEquals(new BigDecimal("150.00").setScale(2, java.math.RoundingMode.HALF_EVEN), updatedBalance);
    }

    @Test
    @DisplayName("Should ignore negative withdrawals")
    void testWithdraw_NegativeAmount() throws SQLException {
        economyManager.withdraw(player, new BigDecimal("-10.00"));
        verify(preparedStatement, never()).executeUpdate();
    }

    @Test
    @DisplayName("Should check if player has enough balance")
    void testHas() throws SQLException {
        economyManager.setBalance(player, new BigDecimal("100.00"));

        assertTrue(economyManager.has(player, new BigDecimal("50.00")));
        assertTrue(economyManager.has(player, new BigDecimal("100.00")));
        assertFalse(economyManager.has(player, new BigDecimal("150.00")));
    }

    @Test
    @DisplayName("Should format amount correctly")
    void testFormat() {
        assertEquals("100.50", economyManager.format(new BigDecimal("100.5")));
        assertEquals("100.00", economyManager.format(new BigDecimal("100")));
        assertEquals("100.56", economyManager.format(new BigDecimal("100.555"))); // HALF_EVEN rounding
    }

    @Test
    @DisplayName("Should format amount with currency symbol")
    void testGetFormattedWithSymbol() {
        lenient().when(config.getString("economy.currency-symbol", "₳")).thenReturn("$");
        lenient().when(config.getString("economy.currencies.Aurels.symbol", "$")).thenReturn("£");

        assertEquals("£100.50", economyManager.getFormattedWithSymbol(new BigDecimal("100.5"), "Aurels"));

        lenient().when(config.getString("economy.currencies.Credits.symbol", "$")).thenReturn("$");
        assertEquals("$50.00", economyManager.getFormattedWithSymbol(new BigDecimal("50.0"), "Credits"));
    }
}

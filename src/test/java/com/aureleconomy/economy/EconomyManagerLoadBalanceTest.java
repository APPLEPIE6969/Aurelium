package com.aureleconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * C4: a balance read that fails must not create a balance.
 *
 * <p>{@code loadBalance} used to fall out of its {@code catch (SQLException)} into
 * the "no account yet" branch, and that branch upserts
 * {@code balance = starting-balance}. One timeout therefore overwrote a player's
 * real balance with the starting balance, permanently.
 *
 * <p>The JDBC objects are mocks here on purpose: the point is which statements are
 * prepared at all. The database answer is scripted per statement, so a read error
 * and a genuinely missing account can be told apart.
 */
class EconomyManagerLoadBalanceTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal STARTING = new BigDecimal("100.00");
    private static final BigDecimal STORED = new BigDecimal("500.00");
    private static final BigDecimal SPENT = new BigDecimal("10.00");

    private Connection connection;
    private PreparedStatement select;
    private PreparedStatement debit;
    private PreparedStatement createBalance;
    private PreparedStatement statement;
    private ResultSet resultSet;

    private EconomyManager economy;
    private OfflinePlayer player;
    private final List<String> preparedSql = new ArrayList<>();

    @BeforeEach
    void openLedger() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        config.set("economy.starting-balance", STARTING.doubleValue());

        AurelEconomy plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        connection = mock(Connection.class);
        select = mock(PreparedStatement.class);
        debit = mock(PreparedStatement.class);
        createBalance = mock(PreparedStatement.class);
        statement = mock(PreparedStatement.class);
        resultSet = mock(ResultSet.class);

        when(connection.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            preparedSql.add(sql);
            if (sql.startsWith("SELECT balance"))
                return select;
            if (sql.startsWith("UPDATE player_balances"))
                return debit;
            if (sql.startsWith("INSERT INTO player_balances"))
                return createBalance;
            return statement;
        });
        when(connection.getAutoCommit()).thenReturn(true);
        when(select.executeQuery()).thenReturn(resultSet);
        when(debit.executeUpdate()).thenReturn(1);
        when(createBalance.executeUpdate()).thenReturn(1);
        when(statement.executeUpdate()).thenReturn(1);

        DatabaseManager database = mock(DatabaseManager.class);
        when(database.getConnection()).thenReturn(connection);
        when(database.isMySQL()).thenReturn(false);
        when(database.getWriteLock()).thenReturn(new Object());
        when(plugin.getDatabaseManager()).thenReturn(database);

        economy = new EconomyManager(plugin);

        player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(new UUID(0x9a11e2L, 0x31L));
    }

    @Test
    @DisplayName("A failed read writes nothing and reports zero")
    void aFailedReadCreatesNoBalance() throws Exception {
        when(select.executeQuery()).thenThrow(new SQLException("connection reset"));

        assertAmount("the balance reported during an outage", BigDecimal.ZERO,
                economy.getBalance(player, CURRENCY));

        assertTrue(startingBalanceWrites().isEmpty(),
                "a failed read created a starting balance: " + startingBalanceWrites());
        verify(createBalance, never()).executeUpdate();
    }

    @Test
    @DisplayName("A failed reload keeps the last known balance and writes nothing")
    void aFailedReloadKeepsTheLastKnownBalance() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBigDecimal("balance")).thenReturn(STORED);
        assertAmount("the balance read before the outage", STORED, economy.getBalance(player, CURRENCY));

        // The debit succeeds, then the reload after it fails. With the old flow the
        // catch fell into the create-account branch and rewrote the row with 100.00.
        when(select.executeQuery()).thenThrow(new SQLException("connection reset"));
        assertTrue(economy.withdraw(player, SPENT, CURRENCY), "the debit itself was refused");

        assertAmount("the balance after a failed reload", STORED, economy.getBalance(player, CURRENCY));
        assertTrue(startingBalanceWrites().isEmpty(),
                "a failed reload wrote a starting balance: " + startingBalanceWrites());
        verify(createBalance, never()).executeUpdate();
    }

    @Test
    @DisplayName("A missing account is created once with the starting balance")
    void aMissingAccountGetsTheStartingBalance() throws Exception {
        when(resultSet.next()).thenReturn(false);

        assertAmount("the new account's balance", STARTING, economy.getBalance(player, CURRENCY));

        assertEquals(1, startingBalanceWrites().size(),
                "the starting balance was written " + startingBalanceWrites().size() + " times");
        verify(createBalance, times(1)).executeUpdate();
    }

    @Test
    @DisplayName("An existing account is read and never rewritten")
    void anExistingAccountIsNotWritten() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBigDecimal("balance")).thenReturn(STORED);

        assertAmount("the stored balance", STORED, economy.getBalance(player, CURRENCY));

        assertTrue(startingBalanceWrites().isEmpty(),
                "an existing balance was rewritten: " + startingBalanceWrites());
        verify(createBalance, never()).executeUpdate();
    }

    // -------------------- helpers --------------------

    private List<String> startingBalanceWrites() {
        return preparedSql.stream()
                .filter(sql -> sql.startsWith("INSERT INTO player_balances"))
                .toList();
    }

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

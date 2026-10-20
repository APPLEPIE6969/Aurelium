package com.aureleconomy.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * F1: the queue in front of the ledger table.
 *
 * <p>The ledger must not be able to change whether money moved, so every test
 * here is about the writer's failure behaviour as much as its success: no
 * database call from {@link LedgerService#record}, nothing thrown when the
 * connection is gone or the batch fails, and a hard bound on what is kept in
 * memory.
 */
class LedgerServiceTest {

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private AurelEconomy plugin;
    private DatabaseManager database;
    private LedgerService ledger;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        database = mock(DatabaseManager.class);
        when(plugin.getDatabaseManager()).thenReturn(database);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        when(database.getWriteLock()).thenReturn(new Object());
        ledger = new LedgerService(plugin);
    }

    private static LedgerService.Entry entry(BigDecimal amount) {
        return new LedgerService.Entry(1_700_000_000_000L, ACTOR, null, "DEPOSIT",
                "IRON_INGOT", amount, "Aurels", new BigDecimal("10.00"), "ref-1", null);
    }

    @Test
    @DisplayName("recording a row touches no database and no connection")
    void recordOnlyQueues() {
        ledger.record(entry(new BigDecimal("5.00")));

        assertEquals(1, ledger.pendingCount());
        assertEquals(0, ledger.droppedCount());
        verify(database, times(0)).getConnection();
    }

    @Test
    @DisplayName("a flush writes the queue as one batch and empties it")
    void flushWritesOneBatch() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenReturn(new int[]{1, 1, 1});

        ledger.record(entry(new BigDecimal("1.00")));
        ledger.record(entry(new BigDecimal("2.00")));
        ledger.record(entry(new BigDecimal("3.00")));

        assertEquals(3, ledger.flush(), "flush must report the rows it wrote");
        assertEquals(0, ledger.pendingCount());
        verify(statement, times(3)).addBatch();
        verify(statement, times(1)).executeBatch();
    }

    @Test
    @DisplayName("a missing connection loses the batch instead of throwing at the caller")
    void missingConnectionIsSwallowed() {
        when(database.getConnection()).thenReturn(null);

        ledger.record(entry(new BigDecimal("1.00")));

        assertEquals(0, ledger.flush());
        assertEquals(1, ledger.droppedCount(), "the lost row must be counted, not hidden");
        assertEquals(0, ledger.pendingCount(), "a lost batch must not be retried forever");
    }

    @Test
    @DisplayName("a database error loses the batch instead of throwing at the caller")
    void databaseErrorIsSwallowed() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeBatch()).thenThrow(new SQLException("database is gone"));

        ledger.record(entry(new BigDecimal("1.00")));
        ledger.record(entry(new BigDecimal("2.00")));

        assertEquals(0, ledger.flush());
        assertEquals(2, ledger.droppedCount());
        assertEquals(0, ledger.pendingCount());
    }

    @Test
    @DisplayName("the queue is bounded, and what it refuses is counted")
    void queueIsBounded() {
        for (int i = 0; i < LedgerService.MAX_PENDING + 5; i++) {
            ledger.record(entry(BigDecimal.ONE));
        }

        assertEquals(LedgerService.MAX_PENDING, ledger.pendingCount());
        assertEquals(5, ledger.droppedCount());
    }

    @Test
    @DisplayName("history maps rows and pages by ten")
    void historyMapsAndPages() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong(1)).thenReturn(9L, 8L);
        when(rs.getLong(2)).thenReturn(1_700_000_000_009L, 1_700_000_000_008L);
        when(rs.getString(3)).thenReturn("BID", "BID_REFUND");
        when(rs.getString(4)).thenReturn(null, null);
        when(rs.getString(5)).thenReturn("IRON_INGOT", "IRON_INGOT");
        when(rs.getBigDecimal(6)).thenReturn(new BigDecimal("-5.00"), new BigDecimal("5.00"));
        when(rs.getString(7)).thenReturn("Aurels", "Aurels");
        when(rs.getBigDecimal(8)).thenReturn(new BigDecimal("95.00"), new BigDecimal("100.00"));
        when(rs.getString(9)).thenReturn("42", "42");

        List<LedgerService.Row> page = ledger.history(ACTOR, 2);

        assertEquals(2, page.size());
        assertEquals("BID", page.get(0).type());
        assertEquals(new BigDecimal("-5.00"), page.get(0).amount());
        assertNull(page.get(0).counterparty());
        verify(statement).setString(1, ACTOR.toString());
        verify(statement).setInt(2, LedgerService.PAGE_SIZE);
        verify(statement).setInt(3, LedgerService.PAGE_SIZE);
    }

    @Test
    @DisplayName("a counterparty is stored as a uuid and a missing one as null")
    void counterpartyIsOptional() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(database.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        ledger.record(new LedgerService.Entry(1L, ACTOR, OTHER, "PAY", null,
                new BigDecimal("-2.00"), "Aurels", new BigDecimal("8.00"), null, null));
        ledger.record(new LedgerService.Entry(2L, ACTOR, null, "DEPOSIT", null,
                new BigDecimal("3.00"), "Aurels", new BigDecimal("11.00"), null, null));

        assertEquals(2, ledger.flush());
        verify(statement).setString(3, OTHER.toString());
        verify(statement).setNull(3, java.sql.Types.VARCHAR);
    }
}

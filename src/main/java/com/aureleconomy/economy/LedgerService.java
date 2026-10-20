package com.aureleconomy.economy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.aureleconomy.AurelEconomy;

/**
 * F1: writes the transaction ledger.
 *
 * <p>Every balance mutation calls {@link #record}, which only queues a row - it
 * never touches the database and never throws, because a log must not be able to
 * change whether money moved. A timer flushes the queue in batches, and
 * {@code onDisable} flushes it one last time.
 *
 * <p>The queue is bounded. If the database is unreachable long enough to fill it,
 * rows are dropped and counted in {@link #droppedCount()} rather than growing the
 * heap while money keeps moving: an audit trail that takes the server down would
 * be worse than a gap in it.
 */
public class LedgerService {

    /** One mutation, as the money API saw it. */
    public record Entry(long ts, UUID actor, UUID counterparty, String type, String itemKey,
            BigDecimal amount, String currency, BigDecimal balanceAfter, String refId, String note) {
    }

    /** One row as read back for an operator. */
    public record Row(long id, long ts, String type, String counterparty, String itemKey,
            BigDecimal amount, String currency, BigDecimal balanceAfter, String refId) {
    }

    /** Rows held between flushes before new ones are dropped. */
    static final int MAX_PENDING = 5000;

    /** Rows per page of {@code /eco history}. */
    public static final int PAGE_SIZE = 10;

    private static final String INSERT = "INSERT INTO transaction_ledger (ts, actor_uuid,"
            + " counterparty_uuid, type, item_key, amount, currency, balance_after, ref_id, note)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final AurelEconomy plugin;
    private final Queue<Entry> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger dropped = new AtomicInteger();

    public LedgerService(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    /** Queues one row. Never throws, never blocks, never touches the database. */
    public void record(Entry entry) {
        if (entry == null) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            dropped.incrementAndGet();
            return;
        }
        pending.add(entry);
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Rows lost because the queue was full or the write failed. */
    public int droppedCount() {
        return dropped.get();
    }

    /**
     * Writes everything queued, in one batch. Called from a timer and from
     * shutdown, so it must tolerate being called with nothing to do.
     *
     * @return the number of rows written
     */
    public int flush() {
        List<Entry> batch = new ArrayList<>();
        Entry entry;
        while ((entry = pending.poll()) != null) {
            batch.add(entry);
        }
        if (batch.isEmpty()) {
            return 0;
        }

        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                dropped.addAndGet(batch.size());
                plugin.getComponentLogger().warn("No database connection; lost " + batch.size()
                        + " ledger rows. The money movements themselves are unaffected.");
                return 0;
            }
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(INSERT)) {
                for (Entry row : batch) {
                    ps.setLong(1, row.ts());
                    setUuid(ps, 2, row.actor());
                    setUuid(ps, 3, row.counterparty());
                    ps.setString(4, row.type());
                    ps.setString(5, row.itemKey());
                    ps.setBigDecimal(6, row.amount());
                    ps.setString(7, row.currency());
                    ps.setBigDecimal(8, row.balanceAfter());
                    ps.setString(9, row.refId());
                    ps.setString(10, row.note());
                    ps.addBatch();
                }
                ps.executeBatch();
                return batch.size();
            } catch (SQLException e) {
                dropped.addAndGet(batch.size());
                plugin.getComponentLogger().error("Lost " + batch.size()
                        + " ledger rows to a database error. The money movements themselves are"
                        + " unaffected, but the audit trail has a gap.", e);
                return 0;
            }
        }
    }

    /**
     * One page of a player's history, newest first. Page numbers start at 1.
     *
     * <p>Called from an async task: it borrows a connection and reads.
     */
    public List<Row> history(UUID actor, int page) throws SQLException {
        int offset = Math.max(0, page - 1) * PAGE_SIZE;
        List<Row> rows = new ArrayList<>();
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            Connection connection = plugin.getDatabaseManager().getConnection();
            if (connection == null) {
                throw new SQLException("no database connection");
            }
            try (connection;
                    PreparedStatement ps = connection.prepareStatement(
                            "SELECT id, ts, type, counterparty_uuid, item_key, amount, currency,"
                                    + " balance_after, ref_id FROM transaction_ledger"
                                    + " WHERE actor_uuid = ? ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, actor.toString());
                ps.setInt(2, PAGE_SIZE);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(
                                rs.getLong(1),
                                rs.getLong(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getBigDecimal(6),
                                rs.getString(7),
                                rs.getBigDecimal(8),
                                rs.getString(9)));
                    }
                }
            }
        }
        return rows;
    }

    private static void setUuid(PreparedStatement ps, int index, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, uuid.toString());
        }
    }
}

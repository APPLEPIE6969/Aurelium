package com.aureleconomy.webstore;

import com.aureleconomy.AurelEconomy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable {@link PurchaseQueue} backed by its own local SQLite file, so queued
 * browser purchases survive a server restart.
 *
 * <p>Deliberately a separate file from the economy database: the queue is a
 * dashboard concern with a different lifetime, and keeping it out of the economy
 * schema avoids a migration for something that holds at most a handful of rows.
 * The file lives in the plugin data folder and is created on first use.
 *
 * <p>sqlite-jdbc is already on the classpath because {@code plugin.yml} declares
 * it under {@code libraries:}, so no extra dependency is needed.
 */
public class SqlitePurchaseQueue implements PurchaseQueue {

    private static final String TABLE = "web_purchases";
    private static final AtomicLong IDS = new AtomicLong();

    private final AurelEconomy plugin;
    private final String url;
    private Connection connection;

    public SqlitePurchaseQueue(AurelEconomy plugin, String fileName) {
        this.plugin = plugin;
        Path file = plugin.getDataFolder().toPath().resolve(fileName).normalize();
        if (!file.startsWith(plugin.getDataFolder().toPath())) {
            throw new IllegalArgumentException("Queue file must stay inside the plugin folder: " + fileName);
        }
        this.url = "jdbc:sqlite:" + file;
    }

    /** Opens the connection and creates the table. Safe to call once. */
    public void open() {
        try {
            connection = DriverManager.getConnection(url);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA busy_timeout=10000");
                st.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "id TEXT PRIMARY KEY, "
                        + "type TEXT NOT NULL, "
                        + "player_uuid TEXT NOT NULL, "
                        + "item_key TEXT, "
                        + "auction_id INTEGER DEFAULT 0, "
                        + "order_id INTEGER DEFAULT 0, "
                        + "amount INTEGER NOT NULL, "
                        + "status TEXT NOT NULL, "
                        + "result TEXT, "
                        + "created_at INTEGER NOT NULL)");
            }
            plugin.getComponentLogger().info("Web purchase queue: " + url);
        } catch (SQLException e) {
            connection = null;
            plugin.getComponentLogger().error("Web purchase queue failed to open " + url
                    + " — falling back to the in-memory queue", e);
        }
    }

    public boolean isOpen() {
        return connection != null;
    }

    private Connection conn() throws SQLException {
        if (connection == null || connection.isClosed()) {
            throw new SQLException("queue not open");
        }
        return connection;
    }

    @Override
    public String enqueue(Type type, UUID player, String itemKey, int auctionId, int orderId, int amount) {
        String id = "q-" + IDS.incrementAndGet() + "-" + Long.toHexString(System.nanoTime());
        try (PreparedStatement ps = conn().prepareStatement(
                "INSERT INTO " + TABLE + " (id, type, player_uuid, item_key, auction_id, order_id,"
                        + " amount, status, created_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, type.name());
            ps.setString(3, player.toString());
            ps.setString(4, itemKey);
            ps.setInt(5, auctionId);
            ps.setInt(6, orderId);
            ps.setInt(7, amount);
            ps.setString(8, PENDING);
            ps.setLong(9, System.currentTimeMillis());
            ps.executeUpdate();
            return id;
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue enqueue failed: " + e.getMessage());
            return null;
        }
    }

    @Override
    public List<Purchase> claimPending(UUID player) {
        List<Purchase> claimed = new ArrayList<>();
        try {
            // IMMEDIATE so the read-then-update cannot interleave with another
            // claim for the same player.
            connection.setAutoCommit(false);
            try {
                List<String> ids = new ArrayList<>();
                try (PreparedStatement ps = conn().prepareStatement(
                        "SELECT id FROM " + TABLE + " WHERE player_uuid = ? AND status = ?")) {
                    ps.setString(1, player.toString());
                    ps.setString(2, PENDING);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getString(1));
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    try (PreparedStatement ps = conn().prepareStatement(
                            "UPDATE " + TABLE + " SET status = ? WHERE id = ? AND status = ?")) {
                        for (String id : ids) {
                            ps.setString(1, PROCESSING);
                            ps.setString(2, id);
                            ps.setString(3, PENDING);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                    for (String id : ids) {
                        Purchase p = readOne(id);
                        if (p != null) {
                            claimed.add(p);
                        }
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue claim failed: " + e.getMessage());
        }
        return claimed;
    }

    private Purchase readOne(String id) throws SQLException {
        try (PreparedStatement ps = conn().prepareStatement(
                "SELECT id, type, player_uuid, item_key, auction_id, order_id, amount, status,"
                        + " result, created_at FROM " + TABLE + " WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    private Purchase map(ResultSet rs) throws SQLException {
        return new Purchase(
                rs.getString("id"),
                Type.valueOf(rs.getString("type")),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("item_key"),
                rs.getInt("auction_id"),
                rs.getInt("order_id"),
                rs.getInt("amount"),
                rs.getLong("created_at"),
                rs.getString("status"),
                rs.getString("result"));
    }

    @Override
    public Purchase find(String purchaseId) {
        try {
            return readOne(purchaseId);
        } catch (SQLException e) {
            return null;
        }
    }

    @Override
    public void complete(String purchaseId, boolean success, String resultJson) {
        try (PreparedStatement ps = conn().prepareStatement(
                "UPDATE " + TABLE + " SET status = ?, result = ? WHERE id = ?")) {
            ps.setString(1, success ? COMPLETED : FAILED);
            ps.setString(2, resultJson);
            ps.setString(3, purchaseId);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue completion failed: " + e.getMessage());
        }
    }

    @Override
    public void prune(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        try (PreparedStatement ps = conn().prepareStatement(
                "DELETE FROM " + TABLE + " WHERE created_at < ? AND status NOT IN (?, ?)")) {
            ps.setLong(1, cutoff);
            ps.setString(2, PENDING);
            ps.setString(3, PROCESSING);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue prune failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue close failed: " + e.getMessage());
        } finally {
            connection = null;
        }
    }
}

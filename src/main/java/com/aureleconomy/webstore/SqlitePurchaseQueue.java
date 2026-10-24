package com.aureleconomy.webstore;

import com.aureleconomy.AurelEconomy;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
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
 * <p>The queue used to hold one JDBC connection for every thread and flip its
 * autoCommit to make a claim. A concurrent enqueue then joined the claim's
 * transaction and could be rolled back after the browser had already been given
 * the purchase id. Each operation now borrows a pooled connection, so a
 * rollback can only ever affect the statement that asked for it.
 *
 * <p>sqlite-jdbc is already on the classpath because {@code plugin.yml} declares
 * it under {@code libraries:}, so no extra dependency is needed.
 */
public class SqlitePurchaseQueue implements PurchaseQueue {

    private static final String TABLE = "web_purchases";
    private static final AtomicLong IDS = new AtomicLong();

    private final AurelEconomy plugin;
    private final Path file;
    private final String url;
    private HikariDataSource pool;

    public SqlitePurchaseQueue(AurelEconomy plugin, String fileName) {
        this.plugin = plugin;
        Path dataFolder = plugin.getDataFolder().toPath();
        this.file = dataFolder.resolve(fileName).normalize();
        if (!file.startsWith(dataFolder)) {
            throw new IllegalArgumentException("Queue file must stay inside the plugin folder: " + fileName);
        }
        this.url = "jdbc:sqlite:" + file;
    }

    /** Opens the pool and creates the table. Safe to call once. */
    public void open() {
        try {
            Files.createDirectories(file.getParent());
            // WAL is a property of the database file, not of a connection, so it
            // is set once here. busy_timeout is per connection and is set again
            // through the pool's init SQL.
            try (Connection setup = DriverManager.getConnection(url);
                    Statement st = setup.createStatement()) {
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

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setDriverClassName("org.sqlite.JDBC");
            config.setPoolName("AureliumWebQueue");
            config.setMaximumPoolSize(6);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(10_000L);
            config.setConnectionInitSql("PRAGMA busy_timeout=10000");
            pool = new HikariDataSource(config);
            plugin.getComponentLogger().info("Web purchase queue: " + url);
        } catch (Exception e) {
            closePool();
            plugin.getComponentLogger().error("Web purchase queue failed to open " + url
                    + " — falling back to the in-memory queue", e);
        }
    }

    public boolean isOpen() {
        return pool != null && !pool.isClosed();
    }

    private Connection conn() throws SQLException {
        if (pool == null || pool.isClosed()) {
            throw new SQLException("queue not open");
        }
        return pool.getConnection();
    }

    @Override
    public String enqueue(Type type, UUID player, String itemKey, int auctionId, int orderId, int amount) {
        String id = "q-" + IDS.incrementAndGet() + "-" + Long.toHexString(System.nanoTime());
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
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
        // One statement does the read and the state change: a row can only be
        // claimed once, and no other thread's work is inside a transaction of
        // ours. The driver bundles SQLite 3.45, which supports RETURNING.
        String sql = "UPDATE " + TABLE + " SET status = ? "
                + "WHERE player_uuid = ? AND status = ? "
                + "RETURNING id, type, player_uuid, item_key, auction_id, order_id, amount, status,"
                + " result, created_at";
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, PROCESSING);
            ps.setString(2, player.toString());
            ps.setString(3, PENDING);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.add(map(rs));
                }
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue claim failed: " + e.getMessage());
        }
        return claimed;
    }

    @Override
    public Purchase find(String purchaseId) {
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, type, player_uuid, item_key, auction_id, order_id, amount, status,"
                                + " result, created_at FROM " + TABLE + " WHERE id = ?")) {
            ps.setString(1, purchaseId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        } catch (SQLException e) {
            return null;
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

    /**
     * Only a claimed purchase can settle, and only once. The status in the
     * WHERE clause is the guard: a second completion, or one arriving after the
     * TTL cancelled the row, matches nothing and is ignored.
     */
    @Override
    public boolean complete(String purchaseId, boolean success, String resultJson) {
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + TABLE + " SET status = ?, result = ? WHERE id = ? AND status = ?")) {
            ps.setString(1, success ? COMPLETED : FAILED);
            ps.setString(2, resultJson);
            ps.setString(3, purchaseId);
            ps.setString(4, PROCESSING);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue completion failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public int expireStale() {
        long cutoff = System.currentTimeMillis() - ttlMs();
        // Processing rows are included: the queue survives restarts but the
        // in-flight map does not, so a crash mid-purchase would otherwise leave
        // the row claimed forever. The fill attempt itself is bounded by seconds,
        // far below this cutoff.
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + TABLE + " SET status = ?, result = ? "
                                + "WHERE status IN (?, ?) AND created_at < ?")) {
            ps.setString(1, FAILED);
            ps.setString(2, EXPIRED_RESULT);
            ps.setString(3, PENDING);
            ps.setString(4, PROCESSING);
            ps.setLong(5, cutoff);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getComponentLogger().warn("Web queue expiry failed: " + e.getMessage());
            return 0;
        }
    }

    private long ttlMs() {
        int minutes = Math.max(1, plugin.getConfig().getInt("web-queue.ttl-minutes", 15));
        return minutes * 60_000L;
    }

    @Override
    public void prune(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
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
        closePool();
    }

    private void closePool() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception e) {
                plugin.getComponentLogger().warn("Web queue close failed: " + e.getMessage());
            } finally {
                pool = null;
            }
        }
    }
}

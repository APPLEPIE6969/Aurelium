package com.aureleconomy.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.repositories.PlayerRepository;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * H1's required test: the plugin's own connection provider under concurrent,
 * interleaved money traffic, on a real SQLite database.
 *
 * <p>What each test pins down:
 *
 * <ul>
 *   <li>{@link #concurrentMoneyOperationsKeepEveryBalanceExact()} runs eight
 *       threads through deposits, withdrawals, the atomic buy-order fill and the
 *       offline-earning log, then asserts the rule 2 sum did not move and that no
 *       fill was counted twice. This is also the nesting guard: the pool holds one
 *       connection, so an operation that borrows a second connection while
 *       holding the first times out, its write is refused, and this test fails.
 *   <li>{@link #closingABorrowReleasesIt()} requires close() to mean release. It
 *       fails on the pre-pool tree, where closing a borrow was deliberately inert
 *       so that one caller could not kill another's connection.
 *   <li>{@link #pragmasSurviveRecycling()} requires every borrowed connection to
 *       carry the journal mode and busy timeout, including one opened by a pool
 *       the earlier borrow never belonged to.
 * </ul>
 *
 * <p>MySQL is not exercised here: the CI jobs that cover MySQL (mysql-test,
 * scanner-mysql-test) start a real server and run the plugin against it, which
 * covers the pooled provider end to end. A unit-level MySQL test would need a
 * second database service in the unit-test job.
 */
class DatabaseConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ROUNDS = 40;
    private static final int FILLS = THREADS * ROUNDS;
    private static final String CURRENCY = "Aurels";
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("100.00");
    private static final BigDecimal ONE_CENT = new BigDecimal("0.01");
    private static final BigDecimal UNIT_PRICE = new BigDecimal("1.00");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    /** The test's own handle, for seeding and for the invariant queries. */
    private Connection observer;
    private int orderId;
    private final List<UUID> accounts = new ArrayList<>();

    @BeforeEach
    void openDatabase() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        // Zero, so every unit of money in this test is one this test put there.
        config.set("economy.starting-balance", 0.0d);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = new EconomyManager(plugin);
        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        for (int i = 0; i < THREADS; i++) {
            UUID id = new UUID(0x1000L + i, 0x2000L + i);
            accounts.add(id);
            try (PreparedStatement ps = observer.prepareStatement(
                    "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
                ps.setString(1, id.toString());
                ps.setString(2, CURRENCY);
                ps.setBigDecimal(3, STARTING_BALANCE);
                ps.executeUpdate();
            }
        }

        // One buy order, escrowed (its unfilled remainder is money the server holds),
        // sized exactly to the number of fills the threads attempt.
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO buy_orders (buyer_uuid, material, amount_requested, amount_filled, "
                        + "price_per_piece, currency, status) VALUES (?, ?, ?, 0, ?, ?, 'ACTIVE')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, accounts.get(0).toString());
            ps.setString(2, "DIAMOND");
            ps.setInt(3, FILLS);
            ps.setBigDecimal(4, UNIT_PRICE);
            ps.setString(5, CURRENCY);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "the seeded buy order has no id");
                orderId = keys.getInt(1);
            }
        }
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        if (observer != null) {
            observer.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    // -------------------- the required test --------------------

    @Test
    @DisplayName("Concurrent deposits, withdrawals, order fills and earnings keep every balance exact")
    void concurrentMoneyOperationsKeepEveryBalanceExact() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            UUID account = accounts.get(t);
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < ROUNDS; round++) {
                        // The balance write, then the metadata write and the cache
                        // refresh. All three are separate borrows in production.
                        assertTrue(economy.deposit(offlinePlayer(account), ONE_CENT, CURRENCY),
                                "deposit was refused for " + account);
                        assertTrue(economy.withdraw(offlinePlayer(account), ONE_CENT, CURRENCY),
                                "withdrawal was refused for " + account);

                        int claimed = claimOneUnit();
                        assertEquals(1, claimed,
                                "the order refused a unit before all " + FILLS + " were claimed");
                        // The escrowed coin moves to the seller: one unit of
                        // escrow disappears as one unit of balance appears.
                        assertTrue(economy.deposit(offlinePlayer(accounts.get(1)), UNIT_PRICE, CURRENCY),
                                "the seller payout was refused");

                        recordOfflineEarning(account);
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "db-worker-" + t);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.MINUTES.toMillis(2));
            assertFalse(thread.isAlive(), thread.getName() + " did not finish");
        }

        if (!failures.isEmpty()) {
            Throwable first = failures.peek();
            throw new AssertionError(failures.size() + " worker(s) failed, first: " + first, first);
        }

        // Rule 2: nothing was minted or burned while the threads ran.
        MoneyInvariants.assertMoneyUnchanged(observer, "the concurrent run", before);

        // The seller received exactly the escrow that was spent, everyone else is
        // where they started.
        BigDecimal sellerExpected = STARTING_BALANCE.add(UNIT_PRICE.multiply(BigDecimal.valueOf(FILLS)));
        assertEquals(0, sellerExpected.compareTo(balanceOf(accounts.get(1))),
                "the seller did not receive exactly one unit price per fill");
        for (int i = 0; i < accounts.size(); i++) {
            if (i == 1) {
                continue;
            }
            assertEquals(0, STARTING_BALANCE.compareTo(balanceOf(accounts.get(i))),
                    "account " + accounts.get(i) + " was not left whole");
        }

        // Every unit was filled exactly once, and the order closed.
        assertEquals(FILLS, count("SELECT amount_filled FROM buy_orders WHERE id = " + orderId),
                "the order was not filled exactly once per claim");
        assertEquals("COMPLETED", statusOfOrder(), "the order did not complete");
        assertEquals(FILLS, count("SELECT COUNT(*) FROM offline_earnings"),
                "an offline earning was lost or duplicated");
    }

    // -------------------- borrow ownership --------------------

    @Test
    @DisplayName("Closing a borrowed connection releases it, a thousand times over")
    void closingABorrowReleasesIt() throws Exception {
        for (int i = 0; i < 1000; i++) {
            Connection borrowed = manager.getConnection();
            assertNotNull(borrowed, "borrow " + i + " was refused");
            assertFalse(borrowed.isClosed(), "borrow " + i + " arrived closed");
            borrowed.close();
            assertTrue(borrowed.isClosed(),
                    "close() must release the borrow, but borrow " + i + " was still open");
        }

        // And the slot is genuinely usable afterwards.
        try (Connection conn = manager.getConnection();
                Statement statement = conn.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM player_balances")) {
            assertTrue(rs.next());
            assertEquals(THREADS, rs.getInt(1));
        }
    }

    // -------------------- per-connection PRAGMAs --------------------

    @Test
    @DisplayName("The players repository releases every borrow it takes")
    void playerRepositoryReleasesBorrows() throws Exception {
        PlayerRepository players = manager.players();
        UUID id = accounts.get(2);

        // Each call takes a borrow; a leak starves the one-connection pool on the
        // very next call, so this fails fast rather than at iteration 200.
        for (int i = 0; i < 200; i++) {
            players.upsert(id, "worker-" + i);
            assertEquals("worker-" + i, players.findName(id).orElse(null),
                    "the stored name was not readable on iteration " + i);
            players.setGuiStyle(id, i % 2 == 0 ? "MODERN" : "CLASSIC");
            assertEquals(i % 2 == 0 ? "MODERN" : "CLASSIC", players.findGuiStyle(id).orElse(null),
                    "the stored gui style was not readable on iteration " + i);
        }
    }

    @Test
    @DisplayName("journal_mode and busy_timeout survive a borrow, a release and a new pool")
    void pragmasSurviveRecycling() throws Exception {
        assertPooledPragmas();

        // A pool the first borrow never belonged to: a genuinely new physical
        // connection, so a PRAGMA that was applied once at startup would be gone.
        manager.close();
        assertTrue(manager.initialize(), "re-initialize must succeed on the same file");
        assertPooledPragmas();
    }

    private void assertPooledPragmas() throws Exception {
        for (int borrow = 0; borrow < 2; borrow++) {
            try (Connection conn = manager.getConnection()) {
                assertEquals("wal", pragma(conn, "journal_mode").toLowerCase(Locale.ROOT),
                        "borrow " + borrow + " lost the journal mode");
                assertEquals("30000", pragma(conn, "busy_timeout"),
                        "borrow " + borrow + " lost the busy timeout");
            }
        }
    }

    // -------------------- helpers --------------------

    /**
     * The same guarded update OrderManager uses to fill a buy order. Returns the
     * number of rows it changed, so a double fill would be visible.
     */
    private int claimOneUnit() throws SQLException {
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE buy_orders SET amount_filled = amount_filled + 1, "
                                + "status = CASE WHEN amount_filled + 1 >= amount_requested "
                                + "THEN 'COMPLETED' ELSE 'ACTIVE' END "
                                + "WHERE id = ? AND status = 'ACTIVE' "
                                + "AND amount_filled + 1 <= amount_requested")) {
            ps.setInt(1, orderId);
            return ps.executeUpdate();
        }
    }

    /** The item-delivery notification OrderManager writes, with no money on it. */
    private void recordOfflineEarning(UUID uuid) throws SQLException {
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO offline_earnings (uuid, amount, item_display, timestamp) "
                                + "VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setBigDecimal(2, BigDecimal.ZERO);
            ps.setString(3, "1x DIAMOND (Buy Order)");
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static OfflinePlayer offlinePlayer(UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("worker-" + id.getLeastSignificantBits());
        return player;
    }

    private BigDecimal balanceOf(UUID account) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT COALESCE(SUM(balance), 0) FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getBigDecimal(1);
            }
        }
    }

    private int count(String sql) throws SQLException {
        try (Statement statement = observer.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "no row for " + sql);
            return rs.getInt(1);
        }
    }

    private String statusOfOrder() throws SQLException {
        try (Statement statement = observer.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT status FROM buy_orders WHERE id = " + orderId)) {
            assertTrue(rs.next(), "the seeded order disappeared");
            return rs.getString(1);
        }
    }

    private static String pragma(Connection conn, String name) throws SQLException {
        try (Statement statement = conn.createStatement();
                ResultSet rs = statement.executeQuery("PRAGMA " + name)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * A single deposit and withdrawal on a cold pool. Each internally writes a
     * balance, a players row and a cache refresh; if any of those borrows nests
     * inside another, the one-connection pool times out, the nested borrow reports
     * null, and the call either refuses the write or fails on the null (which is
     * what the documented null contract means for callers that do not check). The
     * mutation run that moved {@code updatePlayerMetadata} back inside the balance
     * borrow failed here, which is why the borrows are separated.
     */
    @Test
    @DisplayName("A deposit and a withdrawal do not stack borrows on the one-connection pool")
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void depositAndWithdrawDoNotStackBorrows() throws Exception {
        assertTrue(economy.deposit(offlinePlayer(accounts.get(0)), ONE_CENT, CURRENCY),
                "deposit was refused: a nested borrow timed out");
        assertTrue(economy.withdraw(offlinePlayer(accounts.get(0)), ONE_CENT, CURRENCY),
                "withdrawal was refused: a nested borrow timed out");
        assertEquals(0, STARTING_BALANCE.compareTo(balanceOf(accounts.get(0))));
    }
}

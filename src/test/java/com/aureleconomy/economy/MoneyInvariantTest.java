package com.aureleconomy.economy;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.database.schema.BuyOrderSchema;
import com.aureleconomy.database.schema.OfflineEarningSchema;
import com.aureleconomy.database.schema.PlayerBalanceSchema;
import com.aureleconomy.database.types.SQLiteTypes;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The money ledger a money test measures against, so every one of them asserts
 * the same thing (rule 2).
 *
 * <p>Aurelium has no single ledger row for "the money". It is spread over three
 * places, and a fix that creates or destroys value shows up as a change in their
 * sum even when every individual row looks plausible:
 *
 * <ul>
 *   <li>{@code player_balances.balance}, what players hold right now;
 *   <li>{@code offline_earnings.amount}, what the server owes players who were
 *       offline, credited to them later;
 *   <li>the unfilled remainder of every active buy order, which is money held
 *       against an order the server has not filled. This is computed from
 *       {@code buy_orders}, not stored, so the helper derives it the same way the
 *       dashboard does.
 * </ul>
 *
 * <p>What the sum does is move, and the helpers name each case:
 *
 * <ul>
 *   <li>{@link #assertMoneyUnchanged} for value merely moving between accounts,
 *       which is what a transfer, a bid, a cancellation and a claim all do;
 *   <li>{@link #assertMoneyMintedBy} for a deliberate mint, which has to be
 *       named by the test that does it;
 *   <li>{@link #assertMoneyBurnedBy} for a deliberate burn, which a withdrawal
 *       out of thin air is, since nothing in the ledger owes that player anything.
 *       A test that withdraws and expects the sum to be unchanged is asserting
 *       that the money came from nowhere.
 * </ul>
 *
 * <p>The schema is built from the production {@code Schema} classes rather than
 * copied, so a column the production code depends on cannot go missing here and
 * make a test pass for the wrong reason.
 *
 * <p>The connection is a real SQLite file rather than an in-memory one, because
 * {@link EconomyManager#transfer} toggles autocommit and commits and rolls back on
 * the shared connection. It is closed after each test, because an open handle
 * keeps a lock on the file and {@code @TempDir} then fails to delete it.
 *
 * <p>Abstract so JUnit does not try to run it on its own; the setup and every
 * helper here are inherited by the tests that actually assert something.
 */
abstract class MoneyInvariantTest {

    static final String CURRENCY = "aurels";
    static final BigDecimal ZERO = new BigDecimal("0.00");

    @TempDir
    Path tempDir;

    Connection connection;
    EconomyManager economy;
    DatabaseManager databaseManager;
    AurelEconomy plugin;
    /** Exposed so a test can assert on what the plugin logged. */
    ComponentLogger logger;

    @BeforeEach
    void openLedger() throws SQLException {
        File dbFile = tempDir.resolve("ledger.db").toFile();
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(jdbcUrl);

        SQLiteTypes types = new SQLiteTypes();
        try (Statement st = connection.createStatement()) {
            st.execute(new PlayerBalanceSchema(types).create());
            st.execute(new OfflineEarningSchema(types).create());
            st.execute(new BuyOrderSchema(types).create());
        }

        databaseManager = mock(DatabaseManager.class);
        // One fresh connection per borrow, like production: EconomyManager closes
        // the connection it is given, and handing back a single shared handle would
        // close it on the first call and fail the rest of the class. The test's own
        // handle above is kept for seeding and for the invariant queries.
        when(databaseManager.getConnection()).thenAnswer(call -> DriverManager.getConnection(jdbcUrl));
        when(databaseManager.getWriteLock()).thenReturn(new Object());
        when(databaseManager.isMySQL()).thenReturn(false);

        YamlConfiguration config = new YamlConfiguration();
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        // A starting balance of zero, so a path that lazily creates a row cannot
        // mint money the test has not accounted for. Any mint has to be an explicit
        // deposit this test made.
        config.set("economy.starting-balance", 0.0d);

        plugin = mock(AurelEconomy.class);
        when(plugin.getDatabaseManager()).thenReturn(databaseManager);
        when(plugin.getConfig()).thenReturn(config);
        logger = mock(ComponentLogger.class);
        when(plugin.getComponentLogger()).thenReturn(logger);

        economy = new EconomyManager(plugin);
    }

    @AfterEach
    void closeLedger() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    // --- players ------------------------------------------------------------

    /** A mock player, not yet in the ledger. Give it a balance with {@link #credit}. */
    OfflinePlayer player(String name) {
        UUID uuid = UUID.randomUUID();
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn(name);
        return player;
    }

    /** Writes a balance straight into the ledger, bypassing the API and the cache. */
    void seed(OfflinePlayer player, String amount) throws SQLException {
        seed(player, CURRENCY, amount);
    }

    void seed(OfflinePlayer player, String currency, String amount) throws SQLException {
        BigDecimal value = new BigDecimal(amount);
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) "
                        + "ON CONFLICT(uuid, currency) DO UPDATE SET balance = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, currency);
            ps.setBigDecimal(3, value);
            ps.setBigDecimal(4, value);
            ps.executeUpdate();
        }
    }

    /** The balance the database holds, read directly rather than from the cache. */
    BigDecimal dbBalance(OfflinePlayer player) throws SQLException {
        return dbBalance(player, CURRENCY);
    }

    BigDecimal dbBalance(OfflinePlayer player, String currency) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setString(2, currency);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("balance") : null;
            }
        }
    }

    /**
     * Credits through the API, so the ledger and the cache start in agreement.
     *
     * <p>A zero balance is seeded directly, because the API refuses a non-positive
     * deposit by design and a test that wants an empty account should not have to
     * work around that.
     */
    void credit(OfflinePlayer player, String amount) throws SQLException {
        credit(player, CURRENCY, amount);
    }

    void credit(OfflinePlayer player, String currency, String amount) throws SQLException {
        BigDecimal value = new BigDecimal(amount);
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            seed(player, currency, amount);
            return;
        }
        assertTrue(economy.deposit(player, value, currency),
                "seeding a balance should succeed");
    }

    /** Sets the ledger balance behind the API's back, leaving the cache stale on purpose. */
    void setBehindTheApi(OfflinePlayer player, String amount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_balances SET balance = ? WHERE uuid = ?")) {
            ps.setBigDecimal(1, new BigDecimal(amount));
            ps.setString(2, player.getUniqueId().toString());
            ps.executeUpdate();
        }
    }

    // --- the other two places money lives -----------------------------------

    /** Offline earnings owed to a player, for the offline-earnings leg of the sum. */
    void recordOfflineEarning(OfflinePlayer player, String amount) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO offline_earnings (uuid, amount, currency) VALUES (?, ?, ?)")) {
            ps.setString(1, player.getUniqueId().toString());
            ps.setBigDecimal(2, new BigDecimal(amount));
            ps.setString(3, CURRENCY);
            ps.executeUpdate();
        }
    }

    /**
     * An active buy order, which holds
     * {@code pricePerPiece * (amountRequested - amountFilled)} against the server.
     */
    void placeBuyOrder(String pricePerPiece, int requested, int filled) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO buy_orders (buyer_uuid, material, amount_requested, amount_filled, "
                        + "price_per_piece, currency, status) VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, "DIAMOND");
            ps.setInt(3, requested);
            ps.setInt(4, filled);
            ps.setBigDecimal(5, new BigDecimal(pricePerPiece));
            ps.setString(6, CURRENCY);
            ps.executeUpdate();
        }
    }

    // --- rule 2 -------------------------------------------------------------

    /**
     * Everything the server holds on behalf of players, in every place it lives.
     *
     * @see MoneyInvariantTest
     */
    BigDecimal totalMoney() throws SQLException {
        return MoneyInvariants.totalMoney(connection);
    }

    /** The unfilled remainder of every active buy order. */
    BigDecimal orderEscrow() throws SQLException {
        return MoneyInvariants.orderEscrow(connection);
    }

    /** A query returning a single SUM, treating no rows as zero. */
    BigDecimal sum(String sql) throws SQLException {
        return MoneyInvariants.sum(connection, sql);
    }

    /**
     * Scale-insensitive amount comparison. SQLite does not preserve a decimal
     * column's scale, so {@code 60.00} reads back as {@code 60.0} and
     * {@code equals} would fail on the formatting rather than the value.
     */
    static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }

    /**
     * Rule 2: the sum is the same after the action as before, so value only moved
     * between accounts.
     */
    void assertMoneyUnchanged(String phase, BigDecimal before) throws SQLException {
        MoneyInvariants.assertMoneyUnchanged(connection, phase, before);
    }

    /** Rule 2, for a test that deliberately mints: the sum must rise by exactly this. */
    void assertMoneyMintedBy(String phase, BigDecimal before, BigDecimal minted) throws SQLException {
        MoneyInvariants.assertMoneyMintedBy(connection, phase, before, minted);
    }

    /** Rule 2, for a test that deliberately burns: the sum must fall by exactly this. */
    void assertMoneyBurnedBy(String phase, BigDecimal before, BigDecimal burned) throws SQLException {
        MoneyInvariants.assertMoneyBurnedBy(connection, phase, before, burned);
    }
}
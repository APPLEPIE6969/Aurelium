package com.aureleconomy.orders;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;
import com.aureleconomy.market.TradeVolumeTracker;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

/**
 * H6: cancelling a buy order and filling it used to be able to pay out twice.
 * The cancellation refunded the buyer from the in-memory order and wrote the
 * status asynchronously; a fill that claimed the still-ACTIVE row in that
 * window was paid out of escrow that had already gone back to the buyer.
 *
 * <p>The tests drive the real {@link OrderManager} against the real
 * {@link EconomyManager} and a real SQLite database, so the refund, the payout
 * and the invariant are measured where the plugin keeps them. The scheduler is
 * a test clock: it can hold the fill's SQL claim while the cancellation runs,
 * which is the interleaving the old code lost.
 */
class OrderCancelFillRaceTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal START = new BigDecimal("1000.00");
    private static final BigDecimal PRICE = new BigDecimal("10.00");
    private static final UUID BUYER = new UUID(0xb0e1L, 0x31L);
    private static final UUID SELLER = new UUID(0x5e11L, 0x32L);

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private OrderManager orders;
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private BukkitScheduler scheduler;
    private final List<Runnable> queued = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean defer = new AtomicBoolean(false);
    private final Map<UUID, OfflinePlayer> offlinePlayers = new ConcurrentHashMap<>();

    private Player buyer;
    private Player seller;
    private OfflinePlayer buyerAccount;
    private OfflinePlayer sellerAccount;

    @BeforeEach
    void openOrderBook() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        config.set("economy.starting-balance", 0.0d);
        config.set("buy-orders.sales-tax-percent", 0.0d);
        config.set("buy-orders.creation-fee-percent", 0.0d);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = new EconomyManager(plugin);
        when(plugin.getEconomyManager()).thenReturn(economy);

        bukkit = mockStatic(Bukkit.class);
        when(Bukkit.isPrimaryThread()).thenReturn(false);
        scheduler = mock(BukkitScheduler.class);
        when(Bukkit.getScheduler()).thenReturn(scheduler);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(this::schedule);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(this::schedule);
        Server server = mock(Server.class);
        when(server.getScheduler()).thenReturn(scheduler);
        when(plugin.getServer()).thenReturn(server);
        when(Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        when(Bukkit.getOfflinePlayer(any(UUID.class)))
                .thenAnswer(call -> offlinePlayer(call.getArgument(0)));

        when(plugin.getAuctionManager()).thenReturn(mock(AuctionManager.class));
        when(plugin.getTradeVolumeTracker()).thenReturn(mock(TradeVolumeTracker.class));

        orders = new OrderManager(plugin);

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        seedBalance(BUYER, START);
        seedBalance(SELLER, BigDecimal.ZERO);
        buyerAccount = offlinePlayer(BUYER);
        sellerAccount = offlinePlayer(SELLER);
        buyer = player(BUYER, "buyer");
        seller = player(SELLER, "seller");
        playerInventory(seller);
    }

    @AfterEach
    void closeOrderBook() throws Exception {
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

    // --- the database decides ----------------------------------------------

    @Test
    @DisplayName("A cancellation the database no longer owns cannot refund a second time")
    void staleMemoryCannotRefundAgain() throws Exception {
        int orderId = seedOrder(1, PRICE);
        // Another instance already cancelled and refunded this order. This
        // server's memory still lists it as active.
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE buy_orders SET status = 'CANCELLED' WHERE id = ?")) {
            ps.setInt(1, orderId);
            ps.executeUpdate();
        }
        assertTrue(economy.deposit(buyerAccount, PRICE, CURRENCY),
                "the other instance's refund must be in the balance");
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        orders.cancelOrder(buyer, orderId);

        assertAmount("the buyer's balance", START, balanceOf(BUYER));
        MoneyInvariants.assertMoneyUnchanged(observer, "a cancellation the database refused", before);
    }

    @Test
    @DisplayName("A fill whose claim was already in flight is not paid after the refund")
    void fillCannotBePaidOutOfRefundedEscrow() throws Exception {
        int orderId = seedOrder(1, PRICE);
        BigDecimal buyerBefore = balanceOf(BUYER);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
            // The filler has passed the in-memory checks; its SQL claim waits.
            defer.set(true);
            orders.fillOrder(seller, orderId, 1);

            // The buyer cancels while that claim is still in flight.
            orders.cancelOrder(buyer, orderId);

            // Now the queued claim runs, followed by anything it schedules.
            defer.set(false);
            while (!queued.isEmpty()) {
                queued.remove(0).run();
            }
        }

        BigDecimal buyerDelta = balanceOf(BUYER).subtract(buyerBefore);
        BigDecimal sellerBalance = balanceOf(SELLER);
        assertAmount("the refund plus the seller's payout", PRICE, buyerDelta.add(sellerBalance));
        MoneyInvariants.assertMoneyUnchanged(observer, "a cancel racing a fill", before);
    }

    // --- the same race, actually concurrent --------------------------------

    @Test
    @DisplayName("Concurrent cancel and fill pay one side only, and the invariant holds")
    void concurrentCancelAndFillPayOnce() throws Exception {
        for (int iteration = 0; iteration < 10; iteration++) {
            int orderId = seedOrder(1, PRICE);
            BigDecimal buyerBefore = balanceOf(BUYER);
            BigDecimal before = MoneyInvariants.totalMoney(observer);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread filler = new Thread(() -> {
                // Static mocks are thread-local, so each racing thread installs
                // its own view of Bukkit.
                try (MockedStatic<Bukkit> ignoredBukkit = stubBukkitForThisThread();
                        MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class)) {
                    start.await();
                    orders.fillOrder(seller, orderId, 1);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            Thread canceller = new Thread(() -> {
                try (MockedStatic<Bukkit> ignoredBukkit = stubBukkitForThisThread()) {
                    start.await();
                    orders.cancelOrder(buyer, orderId);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });

            filler.start();
            canceller.start();
            start.countDown();
            filler.join(20_000);
            canceller.join(20_000);

            assertNull(failure.get(), "a racing operation threw: " + failure.get());
            MoneyInvariants.assertMoneyUnchanged(observer, "concurrent cancel and fill #" + iteration, before);

            BigDecimal buyerDelta = balanceOf(BUYER).subtract(buyerBefore);
            BigDecimal sellerBalance = balanceOf(SELLER);
            assertAmount("the two payouts in iteration " + iteration, PRICE,
                    buyerDelta.add(sellerBalance));

            if (sellerBalance.signum() > 0) {
                assertTrue(economy.withdraw(sellerAccount, sellerBalance, CURRENCY));
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /** The Bukkit surface one racing thread needs; static mocks are thread-local. */
    private MockedStatic<Bukkit> stubBukkitForThisThread() {
        MockedStatic<Bukkit> mocked = mockStatic(Bukkit.class);
        mocked.when(Bukkit::isPrimaryThread).thenReturn(false);
        mocked.when(Bukkit::getScheduler).thenReturn(scheduler);
        mocked.when(() -> Bukkit.getPlayer(any(UUID.class))).thenReturn(null);
        mocked.when(() -> Bukkit.getOfflinePlayer(any(UUID.class)))
                .thenAnswer(call -> offlinePlayer(call.getArgument(0)));
        return mocked;
    }

    private Object schedule(org.mockito.invocation.InvocationOnMock call) {
        Runnable task = call.getArgument(1);
        if (defer.get()) {
            queued.add(task);
        } else {
            task.run();
        }
        return null;
    }

    private int seedOrder(int amount, BigDecimal price) throws SQLException {
        int orderId;
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO buy_orders "
                                + "(buyer_uuid, material, amount_requested, price_per_piece, currency, status) "
                                + "VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
                        Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, BUYER.toString());
            ps.setString(2, Material.DIAMOND.name());
            ps.setInt(3, amount);
            ps.setBigDecimal(4, price);
            ps.setString(5, CURRENCY);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "the seeded order has no id");
                orderId = keys.getInt(1);
            }
        }
        // Escrow leaves the balance and appears in the order row, so the
        // invariant sees a transfer rather than a mint.
        assertTrue(economy.withdraw(buyerAccount, price.multiply(BigDecimal.valueOf(amount)), CURRENCY));
        orders.loadOrders();
        return orderId;
    }

    private Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        return player;
    }

    private void playerInventory(Player holder) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getSize()).thenReturn(36);
        ItemStack stock = mock(ItemStack.class);
        when(stock.getType()).thenReturn(Material.DIAMOND);
        when(stock.getAmount()).thenReturn(1);
        when(inventory.getItem(0)).thenReturn(stock);
        when(holder.getInventory()).thenReturn(inventory);
    }

    private OfflinePlayer offlinePlayer(UUID id) {
        return offlinePlayers.computeIfAbsent(id, key -> {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getUniqueId()).thenReturn(key);
            when(player.getName()).thenReturn("player-" + key.toString().substring(0, 8));
            return player;
        });
    }

    private void seedBalance(UUID account, BigDecimal amount) throws SQLException {
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        }
    }

    private BigDecimal balanceOf(UUID account) throws SQLException {
        try (Connection conn = manager.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("balance") : null;
            }
        }
    }

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

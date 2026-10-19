package com.aureleconomy.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
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

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;
import com.aureleconomy.gui.ConfirmPurchaseGUI;
import com.aureleconomy.utils.InventoryUtils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * C1's required test: a bid charges the bidder, a refusal charges nobody, and
 * every bid that is displaced is refunded exactly what it paid.
 *
 * <p>The bug: {@code bid()} verified funds with a cache read and never withdrew
 * anything. The winner collected the item for free, every outbid player was
 * refunded a bid they had never paid, and the seller was then paid the winning
 * bid out of nowhere. This test drives the real {@link AuctionManager}, the real
 * {@link EconomyManager} and a real SQLite database, so the money is measured
 * where the plugin keeps it rather than on a mock that would answer whatever it
 * was told.
 *
 * <p>What each test pins down:
 *
 * <ul>
 *   <li>{@link #bidChargesTheBidder()}: the bid leaves the bidder's balance and
 *       nothing else moves. The charge is a burn in rule 2 terms, named as such,
 *       because until the auction ends nothing in the ledger owes that bidder the
 *       money back.
 *   <li>{@link #outbidRefundsExactlyThePreviousBid()}: the displaced bid is
 *       returned in full, so the sum falls by the new bid alone.
 *   <li>{@link #insufficientFundsChangesNothing()}: a bid the database refuses
 *       costs nothing and commits nothing.
 *   <li>{@link #cacheDivergenceRefusesTheBid()}: the case the old API got wrong.
 *       The cache says the bidder can pay and the guarded UPDATE matches no rows,
 *       so the bid must be refused rather than proceeding as paid.
 *   <li>{@link #bidThatLosesTheRowIsRefunded()}: the compensating refund when the
 *       auction row moves between the charge and the update.
 *   <li>{@link #endAuctionPaysTheSellerTheWinningBid()}: the seller receives the
 *       winning bid minus the tax, and the only money missing at the end is that
 *       tax.
 *   <li>{@link #concurrentBidsCreateNoMoney()}: two threads racing the same
 *       auction produce one winning bid and one refunded loser, whichever order
 *       they interleave in.
 *   <li>{@link #buyNowChargesTheBuyerOnce()}: the in-game buy-now flow. This one
 *       is a regression guard for C1's own call-site change rather than for the
 *       old code: the old {@code bid()} charged nothing, so the buy-now path
 *       charged once through its own withdraw. The risk C1 introduces is that the
 *       pre-withdraw is left in place and the buyer is charged twice, and that is
 *       the mutation this test fails on. The two web call sites that had the same
 *       pre-withdraw cannot be driven this way (they post to the cloud backend),
 *       so {@code ci/check_issue_fixes.py} asserts no bid caller withdraws for the
 *       same bid.
 * </ul>
 */
class AuctionManagerMoneyTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal START = new BigDecimal("500.00");
    private static final BigDecimal LISTING_PRICE = new BigDecimal("100.00");
    private static final BigDecimal TAX_PERCENT = new BigDecimal("5.0");
    private static final BigDecimal WINNING_BID = new BigDecimal("120.00");
    private static final BigDecimal HIGHER_BID = new BigDecimal("150.00");
    private static final BigDecimal AFTER_TAX = new BigDecimal("114.00");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private AuctionManager auctions;
    /** The test's own handle, for seeding and for the rule 2 queries. */
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private final Map<UUID, OfflinePlayer> offlinePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();

    private UUID seller;
    private UUID bidderA;
    private UUID bidderB;
    private UUID bidderPoor;
    private AuctionItem listing;

    @BeforeEach
    void openAuctionHouse() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        config.set("economy.starting-balance", 0.0d);
        config.set("auction-house.sales-tax-percent", TAX_PERCENT.doubleValue());

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        // Paper's NamespacedKey(Plugin, String) asks the plugin for its namespace,
        // which is a default interface method, so a mock answers null to it.
        when(plugin.namespace()).thenReturn("aurelium");

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = new EconomyManager(plugin);
        when(plugin.getEconomyManager()).thenReturn(economy);

        // No server in a unit test. The scheduler is mocked so the expiry task
        // and the async writes the manager schedules simply do not run, and the
        // player lookups answer from the maps below.
        bukkit = mockStatic(Bukkit.class);
        stubBukkit();

        // Constructed before the listing is seeded, so the constructor's
        // loadAuctions() has nothing to deserialize.
        auctions = new AuctionManager(plugin);
        when(plugin.getAuctionManager()).thenReturn(auctions);

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        seller = new UUID(0x5e11e2L, 0x1L);
        bidderA = new UUID(0xb1dde2L, 0x2L);
        bidderB = new UUID(0xb1dde2L, 0x3L);
        bidderPoor = new UUID(0xb1dde2L, 0x4L);

        seedBalance(seller, START);
        seedBalance(bidderA, START);
        seedBalance(bidderB, START);
        seedBalance(bidderPoor, new BigDecimal("10.00"));

        listing = new AuctionItem.Builder()
                .id(seedListing())
                .seller(seller)
                .item(mockItem())
                .price(LISTING_PRICE)
                .currency(CURRENCY)
                .isBin(false)
                .expiration(System.currentTimeMillis() + 3_600_000L)
                .startTime(System.currentTimeMillis())
                .build();
    }

    @AfterEach
    void closeAuctionHouse() throws SQLException {
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

    // -------------------- the bid itself --------------------

    @Test
    @DisplayName("A winning bid charges the bidder exactly the bid and leaves the seller alone")
    void bidChargesTheBidder() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        assertEquals(AuctionManager.BidResult.SUCCESS, auctions.bid(listing, bidderA, WINNING_BID));

        assertAmount("the bidder's balance", START.subtract(WINNING_BID), balanceOf(bidderA));
        assertAmount("the seller's balance before the sale", START, balanceOf(seller));
        // Nothing holds the bid until the auction ends, so it leaves the ledger:
        // a named burn, not a movement between accounts.
        MoneyInvariants.assertMoneyBurnedBy(observer, "a winning bid", before, WINNING_BID);

        assertEquals(bidderA, listing.getHighestBidder(), "the in-memory auction did not take the bid");
        assertAmount("the in-memory price", WINNING_BID, listing.getPrice());
        assertAmount("the stored price", WINNING_BID, priceOf(listing.getId()));
        assertEquals(bidderA, highestBidderOf(listing.getId()), "the stored auction did not take the bid");
    }

    @Test
    @DisplayName("A higher bid refunds the outbid player exactly their bid")
    void outbidRefundsExactlyThePreviousBid() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        assertEquals(AuctionManager.BidResult.SUCCESS, auctions.bid(listing, bidderA, WINNING_BID));
        MoneyInvariants.assertMoneyBurnedBy(observer, "the first bid", before, WINNING_BID);

        assertEquals(AuctionManager.BidResult.SUCCESS, auctions.bid(listing, bidderB, HIGHER_BID));

        assertAmount("the outbid player's balance", START, balanceOf(bidderA));
        assertAmount("the new bidder's balance", START.subtract(HIGHER_BID), balanceOf(bidderB));
        // The refund puts the first bid back, so from the start the only money gone
        // is the bid standing now. Measured from just after the first bid the net
        // drop would be only 30, which would hide a lost refund behind a total that
        // still looked plausible.
        MoneyInvariants.assertMoneyBurnedBy(observer, "the bid left standing after a refund", before, HIGHER_BID);
        assertEquals(bidderB, listing.getHighestBidder());
    }

    @Test
    @DisplayName("A bid with insufficient funds is refused and changes nothing")
    void insufficientFundsChangesNothing() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        assertEquals(AuctionManager.BidResult.INSUFFICIENT_FUNDS, auctions.bid(listing, bidderPoor, WINNING_BID));

        assertAmount("the refused bidder's balance", new BigDecimal("10.00"), balanceOf(bidderPoor));
        assertAmount("the stored price", LISTING_PRICE, priceOf(listing.getId()));
        assertNull(highestBidderOf(listing.getId()), "a refused bid wrote a highest bidder");
        MoneyInvariants.assertMoneyUnchanged(observer, "a refused bid", before);

        // An account with no balance row at all is refused the same way.
        UUID ghost = new UUID(0x9052L, 0x9L);
        assertEquals(AuctionManager.BidResult.INSUFFICIENT_FUNDS,
                auctions.bid(listing, ghost, new BigDecimal("1.00")));
        MoneyInvariants.assertMoneyUnchanged(observer, "a bid from an account with no row", before);
    }

    @Test
    @DisplayName("A bid the database refuses to fund is rejected even when the cache says it can pay")
    void cacheDivergenceRefusesTheBid() throws Exception {
        // The cache is told the account can afford anything; the database is then
        // put back to zero behind the API's back. This is the state a web purchase
        // racing this bid leaves behind, and the old API answered "paid" to it.
        economy.setBalance(offlinePlayer(bidderA), new BigDecimal("1000.00"), CURRENCY);
        setBehindTheApi(bidderA, "0.00");

        BigDecimal before = MoneyInvariants.totalMoney(observer);

        assertEquals(AuctionManager.BidResult.INSUFFICIENT_FUNDS, auctions.bid(listing, bidderA, WINNING_BID));

        assertAmount("the bidder's balance", ZERO, balanceOf(bidderA));
        assertAmount("the stored price", LISTING_PRICE, priceOf(listing.getId()));
        assertNull(highestBidderOf(listing.getId()), "a bid the database refused was still committed");
        MoneyInvariants.assertMoneyUnchanged(observer, "a bid refused by the guarded update", before);
    }

    @Test
    @DisplayName("A bid that loses the auction row is refunded in full")
    void bidThatLosesTheRowIsRefunded() throws Exception {
        assertEquals(AuctionManager.BidResult.SUCCESS, auctions.bid(listing, bidderA, HIGHER_BID));

        // The in-memory copy now lags the row, which is the state a writer outside
        // this JVM leaves. The in-memory guard no longer rejects the bid, so the
        // charge happens and the conditional UPDATE is what refuses it.
        listing.setPrice(LISTING_PRICE);
        listing.setHighestBidder(null);

        BigDecimal before = MoneyInvariants.totalMoney(observer);

        assertEquals(AuctionManager.BidResult.OUTBID, auctions.bid(listing, bidderB, WINNING_BID));

        assertAmount("the refunded bidder's balance", START, balanceOf(bidderB));
        assertAmount("the stored price", HIGHER_BID, priceOf(listing.getId()));
        assertEquals(bidderA, highestBidderOf(listing.getId()), "a refused bid overwrote the highest bidder");
        MoneyInvariants.assertMoneyUnchanged(observer, "a bid that lost the row", before);
    }

    @Test
    @DisplayName("Two bidders racing one auction create no money and refund the lower bid")
    void concurrentBidsCreateNoMoney() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        Map<UUID, AuctionManager.BidResult> results = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();

        for (BigDecimal amount : List.of(WINNING_BID, HIGHER_BID)) {
            UUID bidder = amount.equals(WINNING_BID) ? bidderA : bidderB;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    // Mockito's static mocks are thread-scoped, so this thread has
                    // to answer the plugin-context statics for itself.
                    try (MockedStatic<Bukkit> ignored = mockStatic(Bukkit.class)) {
                        stubBukkit();
                        // A short, uneven pause so the two bids interleave
                        // differently from run to run. The assertions below must
                        // hold either way.
                        Thread.sleep(ThreadLocalRandom.current().nextInt(1, 4));
                        results.put(bidder, auctions.bid(listing, bidder, amount));
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
            threads.add(thread);
            thread.start();
        }

        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(20));
            if (thread.isAlive()) {
                // A hung bid would leave the assertions below reading a half-run
                // race, so fail with the stack instead of a puzzling result set.
                StringBuilder dump = new StringBuilder("a bidding thread did not finish; stack:");
                for (StackTraceElement frame : thread.getStackTrace()) {
                    dump.append("\n  at ").append(frame);
                }
                fail(dump.toString());
            }
        }

        assertEquals(List.of(), new ArrayList<>(failures), "a bidding thread threw");
        assertEquals(2, results.size(), "both bids must report an outcome");

        // Two interleavings are legal. Whichever bid reaches the auction first, the
        // higher one has to end up holding it: either the lower bid is accepted and
        // then displaced, or it arrives after the higher one and is refused. What
        // must never differ is the money, which the assertions below pin down.
        assertEquals(AuctionManager.BidResult.SUCCESS, results.get(bidderB),
                "the higher bid was not accepted: " + results);
        assertTrue(results.get(bidderA) == AuctionManager.BidResult.SUCCESS
                        || results.get(bidderA) == AuctionManager.BidResult.OUTBID,
                "the lower bid neither won nor was refused: " + results.get(bidderA));

        assertAmount("the winning bidder's balance", START.subtract(HIGHER_BID), balanceOf(bidderB));
        assertAmount("the losing bidder's balance", START, balanceOf(bidderA));
        assertAmount("the stored price", HIGHER_BID, priceOf(listing.getId()));
        assertEquals(bidderB, highestBidderOf(listing.getId()));
        MoneyInvariants.assertMoneyBurnedBy(observer, "two bids racing one auction", before, HIGHER_BID);
    }

    // -------------------- the sale --------------------

    @Test
    @DisplayName("Ending an auction pays the seller the winning bid minus the tax")
    void endAuctionPaysTheSellerTheWinningBid() throws Exception {
        onlinePlayers.put(seller, mock(Player.class));
        assertEquals(AuctionManager.BidResult.SUCCESS, auctions.bid(listing, bidderA, WINNING_BID));
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        auctions.endAuction(listing);

        assertAmount("the seller's balance", START.add(AFTER_TAX), balanceOf(seller));
        // The winning bid was already out of the ledger, so the payout is a mint
        // of the amount the seller keeps. The tax is the only money that stays gone.
        MoneyInvariants.assertMoneyMintedBy(observer, "the seller payout", before, AFTER_TAX);
    }

    // -------------------- the call site that used to withdraw first --------------------

    @Test
    @DisplayName("Buying an auction through the confirm GUI charges the buyer exactly once")
    void buyNowChargesTheBuyerOnce() throws Exception {
        Player buyer = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(buyer.getUniqueId()).thenReturn(bidderA);
        when(buyer.getName()).thenReturn("buyer");
        when(buyer.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        onlinePlayers.put(bidderA, buyer);
        // The seller stays online so endAuction does not also log an offline
        // earning for the payout, which would count the same money twice.
        onlinePlayers.put(seller, mock(Player.class));
        when(Bukkit.createInventory(any(org.bukkit.inventory.InventoryHolder.class), anyInt(),
                any(Component.class))).thenReturn(mock(Inventory.class));

        when(listing.getItem().clone()).thenReturn(listing.getItem());

        BigDecimal before = MoneyInvariants.totalMoney(observer);

        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(buyer);
        when(click.getSlot()).thenReturn(11);
        when(click.getClickedInventory()).thenReturn(null);

        try (MockedConstruction<ItemStack> ignored = mockConstruction(ItemStack.class);
                MockedStatic<InventoryUtils> inventoryUtils = mockStatic(InventoryUtils.class)) {
            inventoryUtils.when(() -> InventoryUtils.hasSpace(any(Inventory.class), any(ItemStack.class), anyInt()))
                    .thenReturn(true);

            new ConfirmPurchaseGUI(plugin, buyer, listing, LISTING_PRICE, false).handleClick(click);
        }

        // Exactly one charge. Leaving the old withdraw in place before bid() would
        // take this twice, which is the mutation this test is here to catch.
        assertAmount("the buyer's balance", START.subtract(LISTING_PRICE), balanceOf(bidderA));
        assertAmount("the seller's balance", START.add(LISTING_PRICE.subtract(
                LISTING_PRICE.multiply(TAX_PERCENT).divide(new BigDecimal("100")))), balanceOf(seller));
        BigDecimal tax = LISTING_PRICE.multiply(TAX_PERCENT).divide(new BigDecimal("100"));
        MoneyInvariants.assertMoneyBurnedBy(observer, "a buy-now purchase", before, tax);
    }

    // -------------------- helpers --------------------

    /**
     * The plugin-context statics a unit test has to answer for. Mockito's static
     * mocks are scoped to the thread that opened them, so the concurrency test
     * opens its own and calls this again from inside the worker thread.
     */
    private void stubBukkit() {
        when(Bukkit.isPrimaryThread()).thenReturn(false);
        when(Bukkit.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        when(Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> offlinePlayer(call.getArgument(0)));
        when(Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> onlinePlayers.get(call.getArgument(0)));
    }

    private OfflinePlayer offlinePlayer(UUID id) {
        return offlinePlayers.computeIfAbsent(id, key -> {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.getUniqueId()).thenReturn(key);
            when(player.getName()).thenReturn("player-" + key.toString().substring(0, 8));
            return player;
        });
    }

    private ItemStack mockItem() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(1);
        return item;
    }

    private void seedBalance(UUID account, BigDecimal amount) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        }
    }

    private int seedListing() throws SQLException {
        String itemData = Base64.getEncoder().encodeToString(new byte[] { 1 });
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO auctions (seller_uuid, item_data, price, currency, is_bin, expiration, "
                        + "highest_bidder_uuid, ended, collected, listing_fee, start_time, purchase_mode) "
                        + "VALUES (?, ?, ?, ?, 0, ?, NULL, 0, 0, 0, ?, 'STACK')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, seller.toString());
            ps.setString(2, itemData);
            ps.setBigDecimal(3, LISTING_PRICE);
            ps.setString(4, CURRENCY);
            ps.setLong(5, System.currentTimeMillis() + 3_600_000L);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "the seeded auction has no id");
                return keys.getInt(1);
            }
        }
    }

    /** Sets the stored balance behind the API's back, leaving the cache stale. */
    private void setBehindTheApi(UUID account, String amount) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "UPDATE player_balances SET balance = ? WHERE uuid = ? AND currency = ?")) {
            ps.setBigDecimal(1, new BigDecimal(amount));
            ps.setString(2, account.toString());
            ps.setString(3, CURRENCY);
            ps.executeUpdate();
        }
    }

    private BigDecimal balanceOf(UUID account) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("balance") : null;
            }
        }
    }

    private BigDecimal priceOf(int auctionId) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement("SELECT price FROM auctions WHERE id = ?")) {
            ps.setInt(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auction " + auctionId + " is missing");
                return rs.getBigDecimal("price");
            }
        }
    }

    private UUID highestBidderOf(int auctionId) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT highest_bidder_uuid FROM auctions WHERE id = ?")) {
            ps.setInt(1, auctionId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auction " + auctionId + " is missing");
                String stored = rs.getString("highest_bidder_uuid");
                return stored == null ? null : UUID.fromString(stored);
            }
        }
    }

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

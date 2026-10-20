package com.aureleconomy.auction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * C2: accepting an offer must sell the item once, to the buyer who paid for it.
 *
 * <p>The old {@code acceptOffer} marked the offer accepted, checked the buyer's
 * balance from the cache, withdrew without looking at the result, paid the seller
 * unconditionally, and only then tried to hand the item over inside a branch an
 * offline bidder never entered. The seller kept the item, the buyer paid for
 * nothing, and a second offer on the same auction could be accepted the same way.
 *
 * <p>These tests run against a real SQLite file through the real
 * {@link DatabaseManager}, {@link EconomyManager} and {@link AuctionManager}. The
 * only stand-ins are the Bukkit statics a unit test has no server for, and the
 * scheduler is made to run its tasks inline so the asynchronous parts of
 * {@code acceptOffer} finish before the assertions.
 */
class AuctionOfferTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal START = new BigDecimal("500.00");
    private static final BigDecimal LISTING_PRICE = new BigDecimal("100.00");
    private static final BigDecimal OFFER = new BigDecimal("80.00");
    private static final BigDecimal HIGHER_OFFER = new BigDecimal("90.00");
    private static final BigDecimal STANDING_BID = new BigDecimal("120.00");
    private static final BigDecimal POOR = new BigDecimal("10.00");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private AuctionManager auctions;
    /** The test's own handle, for seeding and for the rule 2 queries. */
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<ItemStack> itemStacks;
    private final Map<UUID, OfflinePlayer> offlinePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, Player> onlinePlayers = new ConcurrentHashMap<>();

    private UUID seller;
    private UUID buyerA;
    private UUID buyerB;
    private UUID stranger;
    private UUID poor;
    private int auctionId;

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
        config.set("auction-house.sales-tax-percent", 5.0d);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        when(plugin.namespace()).thenReturn("aurelium");
        when(plugin.isEnabled()).thenReturn(true);

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = new EconomyManager(plugin);
        when(plugin.getEconomyManager()).thenReturn(economy);

        bukkit = mockStatic(Bukkit.class);
        stubBukkit();

        // loadAuctions() deserializes item_data, which reaches ItemStack's static
        // deserializer. There is no registry in a unit test, so it answers with a
        // mock item instead. The item is built before the static stub is installed:
        // creating and stubbing a mock inside an unfinished when(...) breaks Mockito.
        ItemStack loadedItem = mockItem();
        itemStacks = mockStatic(ItemStack.class);
        when(ItemStack.deserializeBytes(any(byte[].class))).thenReturn(loadedItem);

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        seller = new UUID(0x5e11e2L, 0x11L);
        buyerA = new UUID(0xb1dde2L, 0x12L);
        buyerB = new UUID(0xb1dde2L, 0x13L);
        stranger = new UUID(0xb1dde2L, 0x14L);
        poor = new UUID(0xb1dde2L, 0x15L);

        seedBalance(seller, START);
        seedBalance(buyerA, START);
        seedBalance(buyerB, START);
        seedBalance(stranger, START);
        seedBalance(poor, POOR);

        // Seeded before the manager is constructed, so loadAuctions() brings the
        // listing into activeAuctions and getAuctionById() can find it, exactly as
        // it would after a restart.
        auctionId = seedListing();

        auctions = new AuctionManager(plugin);
        when(plugin.getAuctionManager()).thenReturn(auctions);
        assertNotNull(auctions.getAuctionById(auctionId), "the listing did not load as active");
    }

    @AfterEach
    void closeAuctionHouse() throws SQLException {
        if (itemStacks != null) {
            itemStacks.close();
        }
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

    // -------------------- the required cases --------------------

    @Test
    @DisplayName("An offline offer winner finds the item in their collection bin")
    void offlineWinnerCollectsFromTheBin() throws Exception {
        int offerId = placeOffer(buyerA, OFFER);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        acceptOffer(offerId, seller);

        assertAmount("the buyer's balance", START.subtract(OFFER), balanceOf(buyerA));
        assertAmount("the seller's balance", START.add(OFFER), balanceOf(seller));
        // An accepted offer is a sale, not a payout: nothing is minted or burned.
        MoneyInvariants.assertMoneyUnchanged(observer, "an accepted offer", before);

        assertEquals(List.of(auctionId), binIds(buyerA), "the winner's collection bin is wrong");
        assertTrue(binIds(seller).isEmpty(),
                "the seller can collect the item as well as having been paid for it");

        // The listing is out of the active set and the bin query reads the database,
        // so a relogin (or a restart) finds the same row. That is what "delivered"
        // means for an offline buyer.
        assertNull(auctions.getAuctionById(auctionId), "the sold listing is still active");
        assertEquals(List.of(auctionId), binIds(buyerA), "the bin entry did not survive a fresh query");

        assertTrue(endedOf(auctionId), "the auction was not retired after the sale");
        assertFalse(collectedOf(auctionId), "a bin entry must stay collectable");
        assertEquals(buyerA, highestBidderOf(auctionId), "the winner was not recorded on the auction");
        assertAmount("the recorded sale price", OFFER, priceOf(auctionId));
    }

    @Test
    @DisplayName("A second offer on the same auction cannot be accepted after the first one")
    void secondOfferIsRefused() throws Exception {
        int first = placeOffer(buyerA, OFFER);
        int second = placeOffer(buyerB, HIGHER_OFFER);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        acceptOffer(first, seller);
        acceptOffer(second, seller);

        assertAmount("the second buyer's balance", START, balanceOf(buyerB));
        assertAmount("the seller's balance", START.add(OFFER), balanceOf(seller));
        // The second accept charged and then refunded its buyer; nothing was
        // minted, burned or paid twice.
        MoneyInvariants.assertMoneyUnchanged(observer, "a refused second offer", before);
        assertTrue(binIds(buyerB).isEmpty(), "the second buyer received the item as well");
        assertEquals("ACCEPTED", offerStatus(first));
        assertEquals("PENDING", offerStatus(second),
                "the losing offer was accepted without the auction changing hands");
    }

    @Test
    @DisplayName("An auction another instance already sold is not sold again")
    void auctionSoldElsewhereIsNotSoldAgain() throws Exception {
        int offerId = placeOffer(buyerA, OFFER);
        // Another instance sells the auction first. This one still holds the listing
        // in memory, so the sequential test above would be stopped by getAuctionById
        // before any statement ran. Only the conditional UPDATE on the row can refuse
        // this sale, and only that guard stops the buyer being charged and the seller
        // being paid for an item that is already gone.
        try (PreparedStatement ps = observer.prepareStatement("UPDATE auctions SET ended = 1 WHERE id = ?")) {
            ps.setInt(1, auctionId);
            ps.executeUpdate();
        }
        assertNotNull(auctions.getAuctionById(auctionId), "the listing left this instance's memory too early");
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        acceptOffer(offerId, seller);

        assertAmount("the buyer's balance", START, balanceOf(buyerA));
        assertAmount("the seller's balance", START, balanceOf(seller));
        MoneyInvariants.assertMoneyUnchanged(observer, "a sale that lost the race", before);
        assertTrue(binIds(buyerA).isEmpty(), "the buyer received an item that was not sold to them");
        assertEquals("PENDING", offerStatus(offerId));
    }

    @Test
    @DisplayName("An auction won by an offer refunds the standing bid and pays the seller once")
    void standingBidIsRefunded() throws Exception {
        assertEquals(AuctionManager.BidResult.SUCCESS,
                auctions.bid(auctions.getAuctionById(auctionId), buyerB, STANDING_BID));
        int offerId = placeOffer(buyerA, OFFER);
        // The standing bid is already out of the ledger at this point, so a refund
        // is a named mint relative to this total.
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        acceptOffer(offerId, seller);

        assertAmount("the outbid player's balance", START, balanceOf(buyerB));
        assertAmount("the offer buyer's balance", START.subtract(OFFER), balanceOf(buyerA));
        // Exactly the offer. acceptOffer used to call endAuction(), which would have
        // paid the seller the standing bid minus tax on top of the offer.
        assertAmount("the seller's balance", START.add(OFFER), balanceOf(seller));
        MoneyInvariants.assertMoneyMintedBy(observer, "the standing bid refund", before, STANDING_BID);
        assertEquals(buyerA, highestBidderOf(auctionId));
    }

    // -------------------- the refusal paths --------------------

    @Test
    @DisplayName("An offer the buyer cannot pay for changes nothing and stays pending")
    void unaffordableOfferChangesNothing() throws Exception {
        int offerId = placeOffer(poor, OFFER);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        acceptOffer(offerId, seller);

        assertAmount("the buyer's balance", POOR, balanceOf(poor));
        assertAmount("the seller's balance", START, balanceOf(seller));
        MoneyInvariants.assertMoneyUnchanged(observer, "an offer that could not be paid", before);
        assertEquals("PENDING", offerStatus(offerId),
                "the offer was marked dead for a sale that never happened");
        assertFalse(endedOf(auctionId), "the auction was retired without a sale");
        assertTrue(binIds(poor).isEmpty());
        assertNotNull(auctions.getAuctionById(auctionId), "the listing left the active set unsold");
    }

    @Test
    @DisplayName("Only the auction's seller can accept an offer, and only they are paid")
    void onlyTheSellerCanAccept() throws Exception {
        int offerId = placeOffer(buyerA, OFFER);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        acceptOffer(offerId, stranger);

        assertAmount("the buyer's balance", START, balanceOf(buyerA));
        assertAmount("the stranger's balance", START, balanceOf(stranger));
        assertAmount("the seller's balance", START, balanceOf(seller));
        MoneyInvariants.assertMoneyUnchanged(observer, "an offer accepted by a stranger", before);
        assertFalse(endedOf(auctionId));
        assertEquals("PENDING", offerStatus(offerId));
        // The old code paid the Player it was handed, so the stranger's balance
        // would have risen by the offer and the seller would have received nothing.
    }

    // -------------------- helpers --------------------

    /**
     * The plugin-context statics a unit test has to answer for. The scheduler runs
     * its work inline: {@code acceptOffer} loads the offer asynchronously and then
     * completes on the main thread, and {@code markCollected} writes asynchronously,
     * so a scheduler that never runs them would leave every assertion reading
     * pre-flight state.
     */
    private void stubBukkit() {
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(scheduler).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));

        when(Bukkit.getScheduler()).thenReturn(scheduler);
        // The test thread stands in for the main thread. With false here,
        // AuctionGUI.refreshAllViewers() bounces itself onto the scheduler and, with
        // that scheduler running tasks inline, re-enters itself until the stack dies.
        when(Bukkit.isPrimaryThread()).thenReturn(true);
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

    /** Makes an offer through the production path and returns its row id. */
    private int placeOffer(UUID bidder, BigDecimal amount) throws SQLException {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(bidder);
        auctions.makeOffer(auctions.getAuctionById(auctionId), player, amount);
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT id FROM auction_offers WHERE auction_id = ? AND bidder_uuid = ? ORDER BY id DESC")) {
            ps.setInt(1, auctionId);
            ps.setString(2, bidder.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no offer row was written for " + bidder);
                return rs.getInt("id");
            }
        }
    }

    /** Accepts an offer as the given player, the way the offers GUI does. */
    private void acceptOffer(int offerId, UUID playerId) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        auctions.acceptOffer(offerId, player);
    }

    private List<Integer> binIds(UUID playerId) {
        List<Integer> ids = new ArrayList<>();
        for (AuctionItem item : auctions.getCollectionBin(playerId)) {
            ids.add(item.getId());
        }
        return ids;
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

    private BigDecimal priceOf(int id) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement("SELECT price FROM auctions WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auction " + id + " is missing");
                return rs.getBigDecimal("price");
            }
        }
    }

    private UUID highestBidderOf(int id) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT highest_bidder_uuid FROM auctions WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auction " + id + " is missing");
                String stored = rs.getString("highest_bidder_uuid");
                return stored == null ? null : UUID.fromString(stored);
            }
        }
    }

    private boolean endedOf(int id) throws SQLException {
        return boolColumnOf(id, "ended");
    }

    private boolean collectedOf(int id) throws SQLException {
        return boolColumnOf(id, "collected");
    }

    private boolean boolColumnOf(int id, String column) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT " + column + " FROM auctions WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auction " + id + " is missing");
                return rs.getBoolean(column);
            }
        }
    }

    private String offerStatus(int offerId) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT status FROM auction_offers WHERE id = ?")) {
            ps.setInt(1, offerId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "offer " + offerId + " is missing");
                return rs.getString("status");
            }
        }
    }

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

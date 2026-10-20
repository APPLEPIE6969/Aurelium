package com.aureleconomy.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;
import com.aureleconomy.market.TradeVolumeTracker;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * C5: filling a buy order removes exactly what it pays for.
 *
 * <p>{@code countItems} walked {@code getContents()}, which is 41 entries (36
 * storage slots plus four armour slots and the offhand), while {@code removeItems}
 * walked {@code getSize()}, the 36 storage slots. An item in the offhand or in
 * armour was counted and paid for and never removed, so the same item could be
 * sold to buy orders forever.
 *
 * <p>Both now walk the same storage slots, so an offhand or armour item is not
 * sellable at all. The tests below run the real {@link OrderManager} against
 * SQLite and assert the money outcome through {@link MoneyInvariants}: the escrow
 * a fill consumes is exactly the amount the seller is paid.
 */
class OrderManagerFillTest {

    private static final String CURRENCY = "Aurels";
    private static final Material MATERIAL = Material.DIAMOND;
    private static final int REQUESTED = 20;
    private static final int STORAGE = 36;
    private static final BigDecimal UNIT_PRICE = new BigDecimal("2.50");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private OrderManager orders;
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> constructedItems;
    private final List<Component> messages = new ArrayList<>();

    private UUID buyerId;
    private UUID sellerId;
    private int orderId;
    private Player seller;
    /** Bukkit's 41-entry contents array: 0-35 storage, 36-39 armour, 40 offhand. */
    private ItemStack[] contents;

    @BeforeEach
    void openMarket() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        config.set("economy.starting-balance", 0.0d);
        config.set("buy-orders.sales-tax-percent", 0.0d);
        config.set("buy-orders.max-active-orders-per-player", 10);

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(scheduler).runTaskAsynchronously(any(Plugin.class), any(Runnable.class));
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(scheduler).runTask(any(Plugin.class), any(Runnable.class));
        when(server.getScheduler()).thenReturn(scheduler);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = new EconomyManager(plugin);
        when(plugin.getEconomyManager()).thenReturn(economy);
        when(plugin.getTradeVolumeTracker()).thenReturn(mock(TradeVolumeTracker.class));
        when(plugin.getAuctionManager()).thenReturn(mock(AuctionManager.class));

        bukkit = mockStatic(Bukkit.class);
        when(Bukkit.isPrimaryThread()).thenReturn(true);
        when(Bukkit.getScheduler()).thenReturn(scheduler);
        when(Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(call -> offlinePlayer(call.getArgument(0)));

        // fillOrder constructs the stack it hands to the collection bin, and there is
        // no registry in a unit test.
        constructedItems = mockConstruction(ItemStack.class);

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        buyerId = new UUID(0xb0e1L, 0x41L);
        sellerId = new UUID(0x5e11e2L, 0x42L);

        contents = new ItemStack[41];
        seller = mock(Player.class);
        when(seller.getUniqueId()).thenReturn(sellerId);
        when(seller.getName()).thenReturn("seller");
        PlayerInventory inventory = fakeInventory();
        when(seller.getInventory()).thenReturn(inventory);
        doAnswer(call -> {
            messages.add(call.getArgument(0));
            return null;
        }).when(seller).sendMessage(any(Component.class));

        orderId = seedOrder(REQUESTED);
        orders = new OrderManager(plugin);
        orders.loadOrders();
    }

    @AfterEach
    void closeMarket() throws SQLException {
        if (constructedItems != null) {
            constructedItems.close();
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
    @DisplayName("An item in the offhand is not counted and is not sold")
    void offhandItemIsNotSellable() throws Exception {
        contents[40] = stack(MATERIAL, 5);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        orders.fillOrder(seller, orderId, 1);

        assertEquals(0, filledOf(orderId), "the order was filled from the offhand");
        assertAmount("the seller's balance", BigDecimal.ZERO, balanceOf(sellerId));
        assertNotNull(contents[40], "the offhand item disappeared");
        assertEquals(5, contents[40].getAmount(), "the offhand item was changed");
        MoneyInvariants.assertMoneyUnchanged(observer, "a sale from the offhand", before);
        assertEquals("You don't have enough DIAMOND in your inventory.", lastMessage());
    }

    @Test
    @DisplayName("An item in an armour slot is not counted and is not sold")
    void armourItemIsNotSellable() throws Exception {
        contents[36] = stack(MATERIAL, 5);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        orders.fillOrder(seller, orderId, 1);

        assertEquals(0, filledOf(orderId), "the order was filled from an armour slot");
        assertAmount("the seller's balance", BigDecimal.ZERO, balanceOf(sellerId));
        assertNotNull(contents[36], "the armour item disappeared");
        assertEquals(5, contents[36].getAmount(), "the armour item was changed");
        MoneyInvariants.assertMoneyUnchanged(observer, "a sale from an armour slot", before);
    }

    @Test
    @DisplayName("A storage sale removes exactly the items it pays for")
    void storageSaleRemovesAndPaysExactly() throws Exception {
        contents[0] = stack(MATERIAL, 10);
        contents[5] = stack(MATERIAL, 5);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        orders.fillOrder(seller, orderId, 12);

        assertNull(contents[0], "the emptied stack was left in place");
        assertEquals(3, contents[5].getAmount(), "the partial stack was not reduced by the remainder");
        assertAmount("the seller's balance", new BigDecimal("30.00"), balanceOf(sellerId));
        assertEquals(12, filledOf(orderId));
        // Twelve pieces of escrow became thirty coins of payout; nothing was created.
        MoneyInvariants.assertMoneyUnchanged(observer, "a buy-order sale", before);
    }

    @Test
    @DisplayName("An overfill leaves the surplus in the inventory")
    void overfillLeavesTheSurplus() throws Exception {
        contents[0] = stack(MATERIAL, 10);
        rewriteRequested(5);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        orders.fillOrder(seller, orderId, 10);

        assertEquals(5, contents[0].getAmount(), "the surplus did not stay in the inventory");
        assertAmount("the seller's balance", new BigDecimal("12.50"), balanceOf(sellerId));
        assertEquals(5, filledOf(orderId));
        MoneyInvariants.assertMoneyUnchanged(observer, "an overfilled buy order", before);
    }

    // -------------------- helpers --------------------

    /**
     * A player inventory with 36 storage slots backed by the 41-entry contents
     * array Bukkit exposes. Storage reads and writes go through it, so a test can
     * tell what a removal actually touched.
     */
    private PlayerInventory fakeInventory() {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getSize()).thenReturn(STORAGE);
        when(inventory.getContents()).thenReturn(contents);
        when(inventory.getItem(anyInt())).thenAnswer(call -> contents[call.getArgument(0)]);
        doAnswer(call -> {
            contents[call.getArgument(0)] = call.getArgument(1);
            return null;
        }).when(inventory).setItem(anyInt(), any());
        return inventory;
    }

    private ItemStack stack(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        AtomicInteger held = new AtomicInteger(amount);
        when(item.getType()).thenReturn(material);
        when(item.getAmount()).thenAnswer(call -> held.get());
        doAnswer(call -> {
            held.set(call.getArgument(0));
            return null;
        }).when(item).setAmount(anyInt());
        return item;
    }

    private OfflinePlayer offlinePlayer(UUID id) {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("player-" + id.toString().substring(0, 8));
        return player;
    }

    private int seedOrder(int requested) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO buy_orders (buyer_uuid, material, amount_requested, amount_filled, price_per_piece, currency, status) "
                        + "VALUES (?, ?, ?, 0, ?, ?, 'ACTIVE')",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, buyerId.toString());
            ps.setString(2, MATERIAL.name());
            ps.setInt(3, requested);
            ps.setBigDecimal(4, UNIT_PRICE);
            ps.setString(5, CURRENCY);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next(), "the seeded order has no id");
                return keys.getInt(1);
            }
        }
    }

    private void rewriteRequested(int requested) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "UPDATE buy_orders SET amount_requested = ?, amount_filled = 0 WHERE id = ?")) {
            ps.setInt(1, requested);
            ps.setInt(2, orderId);
            ps.executeUpdate();
        }
        orders.loadOrders();
    }

    private int filledOf(int id) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT amount_filled FROM buy_orders WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "order " + id + " is missing");
                return rs.getInt("amount_filled");
            }
        }
    }

    private BigDecimal balanceOf(UUID account) throws SQLException {
        try (PreparedStatement ps = observer.prepareStatement(
                "SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal("balance") : BigDecimal.ZERO;
            }
        }
    }

    private String lastMessage() {
        assertTrue(!messages.isEmpty(), "the fill sent no message");
        return PlainTextComponentSerializer.plainText().serialize(messages.get(messages.size() - 1));
    }

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

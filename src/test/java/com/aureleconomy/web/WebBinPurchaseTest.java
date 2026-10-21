package com.aureleconomy.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;
import com.aureleconomy.utils.InventoryUtils;
import com.aureleconomy.webstore.PurchaseQueue;
import com.google.gson.JsonObject;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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
import org.mockito.MockedStatic;

/**
 * H4's money half: a UNIT-mode BIN purchase from the dashboard charges the
 * quantity it asked for at the unit price, and nothing else moves.
 *
 * <p>The bug is one multiplication away from a hundredfold charge. The browser
 * sent the unit price in {@code amount}, the queue stored it as a quantity, and
 * the executor multiplied by it: a listing priced 100.50 per unit queued a
 * purchase of 100 units. This test drives the real {@link WebPurchaseExecutor},
 * the real {@link AuctionManager#purchaseUnits} and the real
 * {@link EconomyManager} against a real SQLite database, so the charge is
 * measured where the plugin keeps it. A mock would happily agree that 100
 * units were bought.
 *
 * <p>{@code purchaseUnits} dispatches its work through the scheduler. The
 * scheduler here runs that work inline, which is the only way this test can see
 * the charge it makes; it does not change what the code does, only when.
 */
class WebBinPurchaseTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal START = new BigDecimal("1000.00");
    private static final BigDecimal UNIT_PRICE = new BigDecimal("100.50");
    private static final BigDecimal TAX_PERCENT = new BigDecimal("5.0");
    private static final int STOCK = 5;

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private AuctionManager auctions;
    private WebPurchaseExecutor executor;
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private final Map<UUID, OfflinePlayer> offlinePlayers = new ConcurrentHashMap<>();

    private UUID seller;
    private UUID buyer;
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
        when(plugin.namespace()).thenReturn("aurelium");

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        economy = new EconomyManager(plugin);
        when(plugin.getEconomyManager()).thenReturn(economy);

        bukkit = mockStatic(Bukkit.class);
        stubBukkit();

        auctions = new AuctionManager(plugin);
        when(plugin.getAuctionManager()).thenReturn(auctions);
        executor = new WebPurchaseExecutor(plugin, mock(PurchaseQueue.class));

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        seller = new UUID(0x5e11e2L, 0x11L);
        buyer = new UUID(0xb1dde2L, 0x12L);
        seedBalance(seller, START);
        seedBalance(buyer, START);

        // listAuction inserts and adds to the in-memory list; the async task runs
        // inline, so the listing is reachable by id when this returns.
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(STOCK);
        when(item.serializeAsBytes()).thenReturn(new byte[] { 1 });
        when(item.clone()).thenReturn(item);
        auctions.listAuction(seller, item, UNIT_PRICE, CURRENCY, true, 3_600_000L,
                BigDecimal.ZERO, AuctionItem.PurchaseMode.UNIT);
        assertEquals(1, auctions.getActiveAuctions().size(), "the seeded listing did not reach memory");
        listing = auctions.getActiveAuctions().get(0);
        assertEquals(STOCK, listing.getAvailableQuantity());
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

    // --- the charge ---------------------------------------------------------

    @Test
    @DisplayName("Buying one unit charges the unit price, not the price read as a quantity")
    void oneUnitChargesTheUnitPrice() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        JsonObject result = buy(1);

        assertTrue(result.get("success").getAsBoolean(), "the purchase was refused: " + result);
        assertEquals(1, result.get("purchased").getAsInt());
        assertAmount("the buyer's balance", START.subtract(UNIT_PRICE), balanceOf(buyer));

        BigDecimal tax = UNIT_PRICE.multiply(TAX_PERCENT).divide(new BigDecimal("100"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertAmount("the seller's balance", START.add(UNIT_PRICE.subtract(tax)), balanceOf(seller));
        // The charge leaves the ledger and the payout re-enters it; the tax is
        // the only money that stays gone.
        MoneyInvariants.assertMoneyBurnedBy(observer, "the tax on one unit", before, tax);
    }

    @Test
    @DisplayName("Buying three units charges three unit prices")
    void threeUnitsChargeThreeUnitPrices() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);
        BigDecimal cost = UNIT_PRICE.multiply(new BigDecimal("3")).setScale(2);

        JsonObject result = buy(3);

        assertTrue(result.get("success").getAsBoolean(), "the purchase was refused: " + result);
        assertEquals(3, result.get("purchased").getAsInt());
        assertAmount("the buyer's balance", START.subtract(cost), balanceOf(buyer));

        BigDecimal tax = cost.multiply(TAX_PERCENT).divide(new BigDecimal("100"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertAmount("the seller's balance", START.add(cost.subtract(tax)), balanceOf(seller));
        MoneyInvariants.assertMoneyBurnedBy(observer, "the tax on three units", before, tax);
    }

    @Test
    @DisplayName("A quantity above the stock is capped at the stock, and charged for the cap")
    void quantityAboveStockIsCapped() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);
        BigDecimal cost = UNIT_PRICE.multiply(BigDecimal.valueOf(STOCK)).setScale(2);

        JsonObject result = buy(50);

        assertTrue(result.get("success").getAsBoolean(), "the purchase was refused: " + result);
        assertEquals(STOCK, result.get("purchased").getAsInt(),
                "the executor passed an uncapped quantity to the purchase");
        assertAmount("the buyer's balance", START.subtract(cost), balanceOf(buyer));

        BigDecimal tax = cost.multiply(TAX_PERCENT).divide(new BigDecimal("100"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        assertAmount("the seller's balance", START.add(cost.subtract(tax)), balanceOf(seller));
        MoneyInvariants.assertMoneyBurnedBy(observer, "the tax on a capped purchase", before, tax);
    }

    // --- helpers ------------------------------------------------------------

    private JsonObject buy(int quantity) throws Exception {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(buyer);
        when(player.getName()).thenReturn("buyer");
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());

        Method bid = WebPurchaseExecutor.class.getDeclaredMethod(
                "bid", Player.class, purchaseClass(), JsonObject.class);
        bid.setAccessible(true);
        JsonObject result = new JsonObject();
        try (MockedStatic<InventoryUtils> room = mockStatic(InventoryUtils.class)) {
            room.when(() -> InventoryUtils.hasSpace(any(), any(ItemStack.class), anyInt()))
                    .thenReturn(true);
            bid.invoke(executor, player, purchase(listing.getId(), quantity), result);
        }
        return result;
    }

    private static Class<?> purchaseClass() throws ClassNotFoundException {
        return Class.forName("com.aureleconomy.webstore.PurchaseQueue$Purchase");
    }

    private static Object purchase(int auctionId, int quantity) throws Exception {
        Class<?> type = purchaseClass();
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?> typeEnum = Class.forName("com.aureleconomy.webstore.PurchaseQueue$Type");
        @SuppressWarnings({ "unchecked", "rawtypes" })
        Object bidType = Enum.valueOf((Class<Enum>) typeEnum.asSubclass(Enum.class), "BID");
        return ctor.newInstance("p1", bidType, UUID.randomUUID(), null,
                auctionId, 0, quantity, 0L, "pending", null);
    }

    private void stubBukkit() {
        when(Bukkit.isPrimaryThread()).thenReturn(false);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(Bukkit.getScheduler()).thenReturn(scheduler);
        // purchaseUnits and listAuction both dispatch through this call. Running
        // the work inline is what lets this test observe the charge it makes.
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    ((Runnable) call.getArgument(1)).run();
                    return null;
                });
        when(Bukkit.getOfflinePlayer(any(UUID.class)))
                .thenAnswer(call -> offlinePlayer(call.getArgument(0)));
        when(Bukkit.getPlayer(any(UUID.class))).thenAnswer(call -> mock(Player.class));
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
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            ps.setBigDecimal(3, amount);
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

    private static void assertAmount(String what, BigDecimal expected, BigDecimal actual) {
        MoneyInvariants.assertAmount(what, expected, actual);
    }
}

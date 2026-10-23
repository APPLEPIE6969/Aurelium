package com.aureleconomy.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.MoneyInvariants;
import com.aureleconomy.market.MarketItemFactory;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketManager;
import com.aureleconomy.utils.InventoryUtils;
import java.io.File;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * H9's replay window: the dashboard's purchase queue is retried, and the old
 * dedup was a ten-minute map that a restart emptied. The same purchase ID could
 * therefore be delivered and charged twice, once on each side of the restart.
 *
 * <p>This drives the real {@link CloudSyncManager} purchase loop against the
 * real {@link EconomyManager} and a real SQLite database, so the claim and the
 * charge are measured where the plugin keeps them. Only the HTTP confirmation
 * is replaced, because the assertion is about what was executed, not about the
 * dashboard's answer.
 */
class CloudPurchaseDedupTest {

    private static final String CURRENCY = "Aurels";
    private static final BigDecimal START = new BigDecimal("1000.00");
    private static final BigDecimal PRICE = new BigDecimal("10.00");
    private static final UUID BUYER = new UUID(0xb1dde2L, 0x21L);

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private EconomyManager economy;
    private MarketManager market;
    private MarketItems.MarketEntry entry;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack delivered;
    private Connection observer;
    private MockedStatic<Bukkit> bukkit;
    private final Queue<String> confirmations = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void openDashboard() throws Exception {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("economy.default-currency", CURRENCY);
        config.set("economy.currencies." + CURRENCY + ".symbol", "A");
        config.set("economy.starting-balance", 0.0d);
        config.set("web.cloud.server-id", "test-server");
        config.set("web.cloud.api-key", "test-key");
        config.set("web.cloud.url", "http://127.0.0.1:1");

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
        when(Bukkit.isPrimaryThread()).thenReturn(false);
        Player buyer = createPlayer();
        when(Bukkit.getPlayer(BUYER)).thenReturn(buyer);

        market = mock(MarketManager.class);
        entry = new MarketItems.MarketEntry(Material.DIAMOND, PRICE.doubleValue());
        when(market.getBuyPrice("DIAMOND")).thenReturn(PRICE);
        when(market.getCurrency("DIAMOND")).thenReturn(CURRENCY);
        when(market.isBlacklisted(Material.DIAMOND)).thenReturn(false);
        when(plugin.getMarketManager()).thenReturn(market);

        observer = DriverManager.getConnection(
                "jdbc:sqlite:" + new File(dataFolder, "database.db").getAbsolutePath());

        seedBalance(BUYER, START);
    }

    @AfterEach
    void closeDashboard() throws Exception {
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

    // --- the replay ---------------------------------------------------------

    @Test
    @DisplayName("The same purchase ID is charged and delivered once")
    void repeatedIdIsDeliveredOnce() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);
        CloudSyncManager sync = newManager();

        execute(sync, List.of(buy("p-1", "DIAMOND", 1)));

        assertAmount("the balance after the first delivery", START.subtract(PRICE), balanceOf(BUYER));
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        MoneyInvariants.assertMoneyBurnedBy(observer, "a dashboard market purchase", before, PRICE);

        execute(sync, List.of(buy("p-1", "DIAMOND", 1)));

        assertAmount("the balance after the replay", START.subtract(PRICE), balanceOf(BUYER));
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        MoneyInvariants.assertMoneyBurnedBy(observer, "a replayed purchase", before, PRICE);
    }

    @Test
    @DisplayName("A restart still remembers the claim, so the purchase is not replayed")
    void restartStillRemembersTheClaim() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);
        execute(newManager(), List.of(buy("p-1", "DIAMOND", 1)));

        // A new manager over the same database is the restart: the old map
        // would be empty here.
        CloudSyncManager afterRestart = newManager();
        execute(afterRestart, List.of(buy("p-1", "DIAMOND", 1)));

        assertAmount("the balance after the restart", START.subtract(PRICE), balanceOf(BUYER));
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        MoneyInvariants.assertMoneyBurnedBy(observer, "a purchase replayed after a restart", before, PRICE);
    }

    // --- the guards ---------------------------------------------------------

    @Test
    @DisplayName("A blacklisted item is refused on the cloud path, with the refusal recorded")
    void blacklistedItemIsRejected() throws Exception {
        when(market.isBlacklisted(Material.DIAMOND)).thenReturn(true);
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        execute(newManager(), List.of(buy("p-2", "DIAMOND", 1)));

        assertAmount("the balance after a refused purchase", START, balanceOf(BUYER));
        verify(inventory, never()).addItem(any(ItemStack.class));
        MoneyInvariants.assertMoneyUnchanged(observer, "a refused blacklisted purchase", before);

        ProcessedPurchaseStore.Confirmation refusal = confirmation("p-2");
        assertFalse(refusal.success(), "the dashboard must be told the purchase was refused");
        assertEquals("Item not for sale", refusal.spent(), "the refusal lost its reason");
    }

    @Test
    @DisplayName("A malformed entry is refused and the rest of the batch still runs")
    void malformedEntryDoesNotDropTheBatch() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);
        Map<String, Object> noItem = buy("p-3", null, 1);
        Map<String, Object> noPlayer = buy("p-4", "DIAMOND", 1);
        noPlayer.remove("playerUuid");

        execute(newManager(), List.of(noItem, noPlayer, buy("p-5", "DIAMOND", 1)));

        assertAmount("the balance after the valid entry", START.subtract(PRICE), balanceOf(BUYER));
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        MoneyInvariants.assertMoneyBurnedBy(observer, "the one valid entry in the batch", before, PRICE);

        assertFalse(confirmation("p-3").success(), "the missing item field was accepted");
        assertEquals("Item not specified", confirmation("p-3").spent(),
                "the missing item field did not reach its own guard");
        assertFalse(confirmation("p-4").success(), "the missing player was accepted");
        assertTrue(confirmation("p-5").success(), "the valid entry was not reported as a success");
    }

    @Test
    @DisplayName("An entry that throws is refused and the rest of the batch still runs")
    void anEntryThatThrowsDoesNotDropTheBatch() throws Exception {
        execute(newManager(), List.of(buy("p-8", "BOOM", 1), buy("p-9", "DIAMOND", 1)), true);

        assertAmount("the balance after the throwing entry", START.subtract(PRICE), balanceOf(BUYER));
        verify(inventory, times(1)).addItem(any(ItemStack.class));
        assertEquals("Purchase failed", confirmation("p-8").spent(),
                "the throwing entry was not refused by the batch guard");
        assertTrue(confirmation("p-9").success(), "the entry after the failure was dropped");
    }

    @Test
    @DisplayName("An amount outside 1..64 is refused instead of multiplied")
    void amountOutsideTheCapIsRejected() throws Exception {
        BigDecimal before = MoneyInvariants.totalMoney(observer);

        execute(newManager(), List.of(buy("p-6", "DIAMOND", 65)));
        execute(newManager(), List.of(buy("p-7", "DIAMOND", -1)));

        assertAmount("the balance after out-of-range amounts", START, balanceOf(BUYER));
        verify(inventory, never()).addItem(any(ItemStack.class));
        MoneyInvariants.assertMoneyUnchanged(observer, "two out-of-range amounts", before);
        assertFalse(confirmation("p-6").success());
        assertEquals("Invalid amount", confirmation("p-6").spent());
        assertFalse(confirmation("p-7").success());
        assertEquals("Invalid amount", confirmation("p-7").spent());
    }

    // --- helpers ------------------------------------------------------------

    private CloudSyncManager newManager() {
        return new CloudSyncManager(plugin) {
            @Override
            void deliverConfirmation(String purchaseId, boolean success,
                    BigDecimal newBalance, String spent) {
                confirmations.add(purchaseId + ":" + success);
            }
        };
    }

    private void execute(CloudSyncManager sync, List<Map<String, Object>> pending) throws Exception {
        execute(sync, pending, false);
    }

    private void execute(CloudSyncManager sync, List<Map<String, Object>> pending, boolean throwingItem)
            throws Exception {
        Method method = CloudSyncManager.class.getDeclaredMethod("executePurchases", List.class);
        method.setAccessible(true);
        try (MockedStatic<MarketItemFactory> factory = mockStatic(MarketItemFactory.class);
                MockedStatic<InventoryUtils> room = mockStatic(InventoryUtils.class)) {
            factory.when(() -> MarketItemFactory.entryForKey(any(AurelEconomy.class), anyString()))
                    .thenReturn(entry);
            if (throwingItem) {
                factory.when(() -> MarketItemFactory.entryForKey(any(AurelEconomy.class), eq("BOOM")))
                        .thenThrow(new IllegalStateException("this entry is malformed"));
            }
            factory.when(() -> MarketItemFactory.build(any(AurelEconomy.class),
                    any(MarketItems.MarketEntry.class), anyInt())).thenReturn(delivered);
            room.when(() -> InventoryUtils.hasSpace(any(), any(ItemStack.class), anyInt()))
                    .thenReturn(true);
            method.invoke(sync, pending);
        }
    }

    private static Map<String, Object> buy(String id, String itemKey, int amount) {
        Map<String, Object> purchase = new LinkedHashMap<>();
        purchase.put("id", id);
        purchase.put("playerUuid", BUYER.toString());
        purchase.put("type", "buy");
        if (itemKey != null) {
            purchase.put("item", itemKey);
        }
        purchase.put("amount", amount);
        return purchase;
    }

    private Player createPlayer() {
        if (player != null) {
            return player;
        }
        inventory = mock(PlayerInventory.class);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        delivered = mock(ItemStack.class);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(BUYER);
        when(player.getName()).thenReturn("buyer");
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    /** Reads the refusal/success the purchase loop recorded for the dashboard. */
    private ProcessedPurchaseStore.Confirmation confirmation(String purchaseId) {
        ProcessedPurchaseStore store = new ProcessedPurchaseStore(plugin);
        return store.pendingConfirmations().stream()
                .filter(c -> c.purchaseId().equals(purchaseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no confirmation was recorded for " + purchaseId + "; recorded: " + confirmations));
    }

    private void seedBalance(UUID account, BigDecimal amount) throws Exception {
        try (PreparedStatement ps = observer.prepareStatement(
                "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, account.toString());
            ps.setString(2, CURRENCY);
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        }
    }

    private BigDecimal balanceOf(UUID account) throws Exception {
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

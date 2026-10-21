package com.aureleconomy.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.market.MarketManager;
import com.aureleconomy.web.WebPurchaseExecutor;
import com.aureleconomy.webstore.PurchaseQueue;
import com.google.gson.JsonObject;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
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

/**
 * H7: a custom item switched off with {@code /customitems toggle} has to stay
 * switched off, and it has to stop being tradeable.
 *
 * <p>Three separate things were wrong. The registry loaded only
 * {@code enabled = 1} rows, so a disabled item did not exist in memory after a
 * restart; the scanner's next pass then registered it as a brand-new item with
 * {@code enabled = 1} and wrote that over the admin's choice. The flag was
 * never read on any trade path, so a toggled-off item stayed buyable at its
 * cached price. And the market's custom entries were only ever added by
 * {@code register()}, which meant a restart dropped every custom price until a
 * rediscovery hit {@code alreadyExists} and never re-added it.
 *
 * <p>The persistence half runs against real SQLite: save a disabled item, build
 * a second registry, load it back, and assert the flag survived and the market
 * was not told to sell it. The trade half checks the shared guard the buy, sell
 * and auction paths call, plus the two paths that can be driven without a
 * server (the web purchase executor and the auction manager). The sell GUI's
 * inventory work needs a server, so its wiring is covered by
 * {@code ci/check_issue_fixes.py} instead.
 */
class CustomItemToggleTest {

    private static final String ID = "test:ruby";
    private static final String CURRENCY = "coins";
    private static final BigDecimal BUY = new BigDecimal("100.00");
    private static final BigDecimal SELL = new BigDecimal("50.00");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private CustomItemRegistry registry;
    private MarketManager market;
    private MockedStatic<Bukkit> bukkit;
    private BukkitScheduler scheduler;
    private ItemStack stack;

    @BeforeEach
    void openRegistry() {
        File dataFolder = tempDir.resolve("data").toFile();
        assertTrue(dataFolder.mkdirs());

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("custom-items.auto-add-to-market", true);
        config.set("custom-items.enabled", true);
        config.set("economy.default-currency", CURRENCY);
        // A real MarketManager schedules price recovery when dynamic pricing is
        // on, which needs a live scheduler. The test only needs its maps.
        config.set("market.dynamic-pricing", false);
        config.set("market.alerts.enabled", false);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        when(plugin.namespace()).thenReturn("aurelium");

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        market = mock(MarketManager.class);
        when(plugin.getMarketManager()).thenReturn(market);

        EconomyManager economy = mock(EconomyManager.class);
        when(economy.getDefaultCurrency()).thenReturn(CURRENCY);
        when(plugin.getEconomyManager()).thenReturn(economy);

        bukkit = mockStatic(Bukkit.class);
        when(Bukkit.isPrimaryThread()).thenReturn(false);
        scheduler = mock(BukkitScheduler.class);
        when(Bukkit.getScheduler()).thenReturn(scheduler);
        // saveToDatabase and the auction insert both dispatch through this call.
        // Running the work inline is what lets the test see what they wrote.
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    ((Runnable) call.getArgument(1)).run();
                    return null;
                });

        // Material.isAir() reaches the Bukkit registry (asBlockType), which has
        // no implementation in a unit test. A mocked Material keeps the guard
        // on its real path without touching the registry.
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.name()).thenReturn("DIAMOND");
        stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        when(stack.clone()).thenReturn(stack);
        when(stack.isSimilar(any(ItemStack.class))).thenReturn(true);
        when(stack.serializeAsBytes()).thenReturn(new byte[] { 1 });

        registry = new CustomItemRegistry(plugin);
        when(plugin.getCustomItemRegistry()).thenReturn(registry);
    }

    @AfterEach
    void closeRegistry() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    // --- the toggle reaches the market --------------------------------------

    @Test
    @DisplayName("switching an item off removes its market price")
    void disabledItemLeavesTheMarket() {
        registry.upsert(item(false));

        verify(market).removeCustomMarketItem(ID);
        verify(market, never()).addCustomMarketItem(eq(ID), any(CustomMarketItem.class));
    }

    @Test
    @DisplayName("switching an item back on puts its price back")
    void enabledItemReturnsToTheMarket() {
        registry.upsert(item(false));
        registry.upsert(item(true));

        verify(market).addCustomMarketItem(eq(ID), any(CustomMarketItem.class));
    }

    // --- the flag survives a restart ---------------------------------------

    @Test
    @DisplayName("a disabled item is still disabled after a save and reload")
    void disabledStateSurvivesARestart() {
        registry.upsert(item(false));
        registry.saveToDatabase(manager);
        reset(market);

        CustomItemRegistry reloaded = reload();

        CustomMarketItem loaded = reloaded.getById(ID);
        assertNotNull(loaded, "a disabled item must stay in the registry, or the next scan re-adds it as new");
        assertFalse(loaded.isEnabled(), "the reload wrote enabled = 1 over the admin's choice");
        verify(market, never()).addCustomMarketItem(eq(ID), any(CustomMarketItem.class));
        verify(market).removeCustomMarketItem(ID);
    }

    @Test
    @DisplayName("an enabled item comes back enabled and priced")
    void enabledStateSurvivesARestart() {
        registry.upsert(item(true));
        registry.saveToDatabase(manager);
        reset(market);

        CustomItemRegistry reloaded = reload();

        CustomMarketItem loaded = reloaded.getById(ID);
        assertNotNull(loaded);
        assertTrue(loaded.isEnabled());
        verify(market).addCustomMarketItem(eq(ID), any(CustomMarketItem.class));
    }

    // --- the trade paths ----------------------------------------------------

    @Test
    @DisplayName("the shared guard sees a disabled item and stops seeing it when re-enabled")
    void disabledItemIsNotTradeable() {
        registry.upsert(item(false));
        assertTrue(registry.isDisabledCustomItem(stack), "a disabled custom item must not pass the trade guard");

        registry.upsert(item(true));
        assertFalse(registry.isDisabledCustomItem(stack), "re-enabling must restore trading");
    }

    @Test
    @DisplayName("the web purchase path refuses a disabled item before it prices it")
    void disabledItemCannotBeBoughtFromTheWeb() throws Exception {
        registry.upsert(item(false));
        WebPurchaseExecutor executor = new WebPurchaseExecutor(plugin, mock(PurchaseQueue.class));

        JsonObject result = new JsonObject();
        Method buy = WebPurchaseExecutor.class.getDeclaredMethod(
                "buy", Player.class, PurchaseQueue.Purchase.class, JsonObject.class);
        buy.setAccessible(true);
        boolean bought = (boolean) buy.invoke(executor, mock(Player.class), purchase(1), result);

        assertFalse(bought);
        assertTrue(result.has("error"), "the browser needs a reason: " + result);
        assertTrue(result.get("error").getAsString().contains("disabled"),
                "the refusal should say the item is disabled: " + result);
    }

    @Test
    @DisplayName("the auction house refuses to list a disabled item and the fee is refunded")
    void disabledItemCannotBeListed() throws Exception {
        registry.upsert(item(false));

        DatabaseManager db = mock(DatabaseManager.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(db.getConnection()).thenReturn(conn);
        when(db.getWriteLock()).thenReturn(new Object());
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);
        when(plugin.getDatabaseManager()).thenReturn(db);

        EconomyManager economy = mock(EconomyManager.class);
        when(economy.getDefaultCurrency()).thenReturn(CURRENCY);
        when(plugin.getEconomyManager()).thenReturn(economy);
        when(plugin.isEnabled()).thenReturn(true);
        // The callback runs through runTask; the second isPrimaryThread() call is
        // AuctionGUI.refreshAllViewers asking whether it is on the main thread,
        // and answering true stops it from rescheduling itself forever.
        when(Bukkit.isPrimaryThread()).thenReturn(false, true);
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class))).thenAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        });

        AuctionManager auctions = new AuctionManager(plugin);
        reset(conn);

        AtomicReference<AuctionItem> listed = new AtomicReference<>();
        auctions.listAuction(UUID.randomUUID(), stack, new BigDecimal("10.00"), CURRENCY,
                true, 60_000L, BigDecimal.ZERO, AuctionItem.PurchaseMode.STACK, listed::set);

        assertNull(listed.get(), "a disabled item was listed");
        // The insert uses the two-argument prepareStatement (RETURN_GENERATED_KEYS),
        // so checking the one-argument overload would pass whether or not the
        // guard exists.
        verify(conn, never()).prepareStatement(contains("INSERT INTO auctions"), anyInt());
    }

    @Test
    @DisplayName("the market refuses to price a disabled item even if a caller adds it")
    void marketRefusesToPriceADisabledItem() {
        MarketManager real = new MarketManager(plugin);

        real.addCustomMarketItem(ID, item(false));

        assertEquals(0, real.getBuyPrice(ID).compareTo(BigDecimal.ZERO),
                "a disabled item must have no buy price");
        assertEquals(0, real.getSellPrice(ID).compareTo(BigDecimal.ZERO),
                "a disabled item must have no sell price");
        assertFalse(real.getEntryCache().containsKey(ID),
                "a disabled item must not be in the entry cache");
    }

    // --- helpers ------------------------------------------------------------

    private CustomItemRegistry reload() {
        CustomItemRegistry reloaded = new CustomItemRegistry(plugin);
        try (MockedStatic<ItemStack> items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(any())).thenReturn(stack);
            reloaded.loadFromDatabase(manager);
        }
        return reloaded;
    }

    private CustomMarketItem item(boolean enabled) {
        return new CustomMarketItem.Builder()
                .canonicalId(ID)
                .itemStack(stack)
                .sourcePlugin("test")
                .displayName("Ruby")
                .category("CUSTOM_ITEMS")
                .buyPrice(BUY)
                .sellPrice(SELL)
                .enabled(enabled)
                .build();
    }

    private static PurchaseQueue.Purchase purchase(int amount) throws Exception {
        Constructor<PurchaseQueue.Purchase> ctor = PurchaseQueue.Purchase.class.getDeclaredConstructor(
                String.class, PurchaseQueue.Type.class, UUID.class, String.class, int.class,
                int.class, int.class, long.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance("p1", PurchaseQueue.Type.BUY, UUID.randomUUID(), ID,
                0, 0, amount, 0L, "PENDING", null);
    }
}

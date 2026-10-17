package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.market.MarketManager;
import com.aureleconomy.webstore.PurchaseQueue;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The web buy path may not deliver an item the server did not charge for (H3c).
 *
 * <p>{@code buy} used to call {@code withdraw} and ignore the answer, then hand
 * over the item unconditionally. {@code withdraw} returned void and wrote
 * asynchronously, so it could not honestly answer the question anyway: it decided
 * affordability from the balance cache, and when the cache and the database
 * disagreed the guarded {@code UPDATE ... AND balance >= ?} matched no rows, the
 * old code reloaded the balance and gave up quietly, and the buyer still received
 * the item. That is a free item on every web purchase that raced another one on
 * the same account.
 *
 * <p>Two things had to change together. {@code withdraw} now returns the real row
 * count of the write (C3-core), and this path checks it. Neither is sufficient on
 * its own: the honest return value is what makes the check possible, and the check
 * is what turns the honest return value into a correct outcome.
 *
 * <p>{@code buy} is private and its {@code Purchase} argument is package private in
 * another package, so both are reached reflectively, the same way
 * {@link WebSellIdentityTest} reaches {@code sell}.
 */
class WebBuyChargeTest {

    private static final String SPAWNER_KEY = "SPAWNER_IRON_GOLEM_SPAWNER";
    private static final BigDecimal PRICE = new BigDecimal("750000");

    private AurelEconomy plugin;
    private MarketManager market;
    private EconomyManager economy;
    private Player buyer;
    private PlayerInventory inventory;
    private CreatureSpawner spawnerState;
    private WebPurchaseExecutor executor;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        market = mock(MarketManager.class);
        economy = mock(EconomyManager.class);

        Map<String, MarketEntry> cache = new HashMap<>();
        cache.put(SPAWNER_KEY, new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner"));
        when(plugin.getMarketManager()).thenReturn(market);
        when(market.getEntryCache()).thenReturn(cache);
        when(market.getBuyPrice(SPAWNER_KEY)).thenReturn(PRICE);
        when(market.getCurrency(SPAWNER_KEY)).thenReturn("coins");

        when(plugin.getEconomyManager()).thenReturn(economy);
        when(economy.has(any(OfflinePlayer.class), any(BigDecimal.class), anyString()))
                .thenReturn(true);
        when(economy.getFormattedWithSymbol(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0).toPlainString() + " coins");
        when(economy.getBalance(any(OfflinePlayer.class), anyString())).thenReturn(BigDecimal.ZERO);
        // The charge succeeds unless a test says otherwise.
        when(economy.withdraw(any(OfflinePlayer.class), any(BigDecimal.class), anyString()))
                .thenReturn(true);

        buyer = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(buyer.getInventory()).thenReturn(inventory);
        // Empty inventory, so InventoryUtils.hasSpace has room to work with.
        when(inventory.getStorageContents()).thenReturn(new ItemStack[41]);
        when(inventory.getSize()).thenReturn(36);
        when(inventory.addItem(any(ItemStack.class)))
                .thenAnswer(i -> new HashMap<Integer, ItemStack>());

        spawnerState = mock(CreatureSpawner.class);

        executor = new WebPurchaseExecutor(plugin, mock(PurchaseQueue.class));
    }

    private MockedConstruction<ItemStack> intercept() {
        return mockConstruction(ItemStack.class, (stack, ctx) -> {
            when(stack.getType()).thenReturn(Material.SPAWNER);
            BlockStateMeta meta = mock(BlockStateMeta.class);
            when(meta.getBlockState()).thenReturn(spawnerState);
            when(stack.getItemMeta()).thenReturn(meta);
        });
    }

    // --- the money hole -----------------------------------------------------

    @Test
    @DisplayName("a refused charge delivers nothing at all")
    void refusedChargeDeliversNothing() throws Exception {
        when(economy.withdraw(any(OfflinePlayer.class), any(BigDecimal.class), anyString()))
                .thenReturn(false);

        JsonObject result = buy(buyer, SPAWNER_KEY, 1);

        assertFalse(result.has("success"),
                "the buyer received the item even though the charge was refused");
        assertTrue(result.has("error"));
        assertTrue(result.get("error").getAsString().contains("Not enough funds"),
                "the refusal should say why: " + result.get("error"));
        verify(inventory, never()).addItem(any(ItemStack.class));
        // Volume must not be recorded either, or the market price moves for a sale
        // that never happened.
        verify(market, never()).onTransaction(anyString(), anyBoolean(), anyInt());
    }

    @Test
    @DisplayName("a refused charge moves no trade volume")
    void refusedChargeRecordsNoVolume() throws Exception {
        when(economy.withdraw(any(), any(BigDecimal.class), anyString())).thenReturn(false);

        buy(buyer, SPAWNER_KEY, 4);

        verify(market, never()).onTransaction(anyString(), anyBoolean(), anyInt());
    }

    // --- the path that must still work --------------------------------------

    @Test
    @DisplayName("an accepted charge delivers the item and records the volume")
    void acceptedChargeDeliversTheItem() throws Exception {
        JsonObject result = buy(buyer, SPAWNER_KEY, 1);

        assertTrue(result.get("success").getAsBoolean());
        verify(buyer.getInventory()).addItem(any(ItemStack.class));
        verify(spawnerState).setSpawnedType(EntityType.IRON_GOLEM);

        verify(economy).withdraw(eq(buyer), eq(PRICE), eq("coins"));
        verify(market).onTransaction(SPAWNER_KEY, true, 1);
    }

    @Test
    @DisplayName("an unknown key is refused before any charge is attempted")
    void unknownKeyIsRefused() throws Exception {
        JsonObject result = buy(buyer, "NOT_A_REAL_ITEM", 1);

        assertFalse(result.has("success"));
        verify(economy, never()).withdraw(any(), any(BigDecimal.class), anyString());
        verify(inventory, never()).addItem(any(ItemStack.class));
    }

    // --- reflection plumbing ------------------------------------------------

    /**
     * Runs {@code buy} with the item construction intercepted and the room check
     * stubbed out.
     *
     * <p>{@code InventoryUtils.hasSpace} is a real static call that asks the
     * material for its max stack size, which reaches {@code org.bukkit.Registry}
     * and throws with no server running. Stubbing it keeps this test about the
     * charge; inventory room is not what is under test here.
     */
    private JsonObject buy(Player player, String itemKey, int amount) throws Exception {
        Method buy = WebPurchaseExecutor.class.getDeclaredMethod(
                "buy", Player.class, purchaseClass(), JsonObject.class);
        buy.setAccessible(true);
        JsonObject result = new JsonObject();

        try (MockedStatic<com.aureleconomy.utils.InventoryUtils> room =
                     Mockito.mockStatic(com.aureleconomy.utils.InventoryUtils.class);
             MockedConstruction<ItemStack> scope = intercept()) {
            room.when(() -> com.aureleconomy.utils.InventoryUtils.hasSpace(
                    any(), any(ItemStack.class), anyInt())).thenReturn(true);
            buy.invoke(executor, player, purchase(itemKey, amount), result);
        }
        return result;
    }

    private static Class<?> purchaseClass() throws ClassNotFoundException {
        return Class.forName("com.aureleconomy.webstore.PurchaseQueue$Purchase");
    }

    private static Object purchase(String itemKey, int amount) throws Exception {
        Class<?> type = purchaseClass();
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?> typeEnum = Class.forName("com.aureleconomy.webstore.PurchaseQueue$Type");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object buyType = Enum.valueOf((Class<Enum>) typeEnum.asSubclass(Enum.class), "BUY");
        return ctor.newInstance(
                "p1", buyType, UUID.randomUUID(), itemKey,
                0, 0, amount, 0L, "pending", null);
    }
}
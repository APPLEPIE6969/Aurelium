package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.market.MarketManager;
import com.aureleconomy.webstore.PurchaseQueue;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

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
 * Covers the web sell path refusing to pay for an item the player does not
 * actually have (H3).
 *
 * <p>A named market entry shares its material with every other entry of the same
 * kind: all spawners are {@link Material#SPAWNER} and all enchanted books are
 * {@link Material#ENCHANTED_BOOK}. The old code resolved the key to a material
 * and then counted and removed stacks by {@code getType()} alone, so a player
 * holding one bare spawner could sell it as "Iron Golem Spawner" over and over
 * and be paid the iron golem spawner price each time. This is the mirror of the
 * buy-side bug, where the player was charged correctly and given less.
 *
 * <p>{@code sell} is private and its {@code Purchase} argument is package private
 * in another package, so both are reached reflectively, the same way
 * {@code ConfigMigrationTest} reaches {@code upgradeConfig}.
 *
 * <p>Stacks are intercepted with {@code mockConstruction} because
 * {@code new ItemStack} needs a live Bukkit registry, the same reason every
 * other test here mocks ItemStack. The comparison itself, {@code isSimilar}
 * against the entry's real item, is what these tests pin down.
 */
class WebPurchaseExecutorSellTest {

    private static final String SPAWNER_KEY = "SPAWNER_IRON_GOLEM_SPAWNER";
    private static final BigDecimal SPAWNER_SELL_PRICE = new BigDecimal("500000");
    private static final int SLOTS = 41;

    private AurelEconomy plugin;
    private MarketManager market;
    private EconomyManager economy;
    private Player seller;
    private PlayerInventory inventory;
    private ItemStack[] contents;
    private CreatureSpawner spawnerState;
    private BlockStateMeta spawnerMeta;
    private WebPurchaseExecutor executor;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        market = mock(MarketManager.class);
        economy = mock(EconomyManager.class);

        Map<String, MarketEntry> cache = new HashMap<>();
        cache.put(SPAWNER_KEY, new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner"));
        when(plugin.getMarketManager()).thenReturn(market);
        when(market.getEntryCache()).thenReturn(cache);
        when(market.getSellPrice(SPAWNER_KEY)).thenReturn(SPAWNER_SELL_PRICE);
        when(market.getCurrency(SPAWNER_KEY)).thenReturn("coins");

        when(plugin.getEconomyManager()).thenReturn(economy);
        when(economy.getFormattedWithSymbol(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0).toPlainString() + " coins");
        when(economy.getBalance(any(OfflinePlayer.class), anyString()))
                .thenReturn(BigDecimal.valueOf(500_000));

        seller = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        contents = new ItemStack[SLOTS];
        when(seller.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(contents);

        spawnerState = mock(CreatureSpawner.class);
        spawnerMeta = mock(BlockStateMeta.class);
        when(spawnerMeta.getBlockState()).thenReturn(spawnerState);

        executor = new WebPurchaseExecutor(plugin, mock(PurchaseQueue.class));
    }

    /**
     * A stack in {@code slot} holding {@code amount}.
     *
     * <p>Both a bare spawner and an iron golem spawner report
     * {@link Material#SPAWNER} from {@code getType()}, which is the whole reason
     * a type check cannot tell them apart. Only {@code isSimilar} does, so
     * {@code sameItem} drives that alone.
     */
    private ItemStack held(int slot, int amount, boolean sameItem) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.SPAWNER);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.isSimilar(any(ItemStack.class))).thenReturn(sameItem);
        contents[slot] = stack;
        return stack;
    }

    /** Intercepts the factory-built stack for the entry being sold. */
    private MockedConstruction<ItemStack> intercept() {
        return mockConstruction(ItemStack.class, (stack, ctx) -> {
            when(stack.getType()).thenReturn(Material.SPAWNER);
            when(stack.getItemMeta()).thenReturn(spawnerMeta);
        });
    }

    // --- the vulnerability --------------------------------------------------

    @Test
    @DisplayName("a bare spawner cannot be sold as an iron golem spawner")
    void bareSpawnerIsRefused() throws Exception {
        held(0, 1, false);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept()) {
            result = sell(seller, SPAWNER_KEY, 1);

            assertNotNull(spawnerState, "the entry's real item should still be built");
            verify(spawnerState).setSpawnedType(EntityType.IRON_GOLEM);
        }

        assertFalse(result.has("success"),
                "a bare spawner was accepted as an iron golem spawner");
        assertTrue(result.has("error"));
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    @DisplayName("a bare spawner does not count towards an iron golem spawner sale")
    void bareSpawnerDoesNotCountTowardsTheTotal() throws Exception {
        held(0, 1, false);
        held(1, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept()) {
            result = sell(seller, SPAWNER_KEY, 2);
        }

        assertFalse(result.has("success"),
                "a bare spawner was counted towards an iron golem spawner sale");
        assertEquals("You only have 1x SPAWNER", result.get("error").getAsString());
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    @DisplayName("only real iron golem spawners are taken from a mixed inventory")
    void mixedInventoryRemovesOnlyRealOnes() throws Exception {
        ItemStack bare = held(0, 1, false);
        ItemStack golem = held(1, 2, true);

        try (MockedConstruction<ItemStack> scope = intercept()) {
            assertTrue(sell(seller, SPAWNER_KEY, 2).get("success").getAsBoolean());
        }

        verify(inventory, never()).setItem(eq(0), any());
        verify(bare, never()).setAmount(anyInt());
        verify(inventory).setItem(1, null);
        verify(golem, never()).setAmount(anyInt());
    }

    @Test
    @DisplayName("an unknown key is refused before any payout")
    void unknownKeyIsRefused() throws Exception {
        held(0, 1, true);

        JsonObject result = sell(seller, "NOT_A_REAL_ITEM", 1);

        assertFalse(result.has("success"));
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("selling more than is held is refused and pays nothing")
    void notEnoughHeldIsRefused() throws Exception {
        held(0, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept()) {
            result = sell(seller, SPAWNER_KEY, 2);
        }

        assertFalse(result.has("success"));
        assertEquals("You only have 1x SPAWNER", result.get("error").getAsString());
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
    }

    // --- the path that must still work --------------------------------------

    @Test
    @DisplayName("a real iron golem spawner sells and pays the entry price")
    void realSpawnerIsSold() throws Exception {
        held(0, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept()) {
            result = sell(seller, SPAWNER_KEY, 1);
        }

        assertTrue(result.get("success").getAsBoolean());
        assertEquals(1, result.get("amount").getAsInt(), "one spawner was sold");
        assertEquals("500000 coins", result.get("earned").getAsString());
        verify(economy).deposit(eq(seller), eq(SPAWNER_SELL_PRICE), eq("coins"));
        verify(inventory).setItem(0, null);
    }

    @Test
    @DisplayName("only the sold amount is removed, leaving the rest of the stack")
    void partialRemovalLeavesTheRemainder() throws Exception {
        ItemStack golem = held(0, 5, true);

        try (MockedConstruction<ItemStack> scope = intercept()) {
            assertTrue(sell(seller, SPAWNER_KEY, 2).get("success").getAsBoolean());
        }

        verify(golem).setAmount(3);
        verify(inventory).setItem(0, golem);
        verify(economy).deposit(eq(seller), eq(new BigDecimal("1000000")), eq("coins"));
    }

    @Test
    @DisplayName("a sale spans several stacks and stops once the amount is reached")
    void saleSpansStacks() throws Exception {
        ItemStack first = held(0, 2, true);
        ItemStack second = held(4, 2, true);
        ItemStack third = held(9, 2, true);

        try (MockedConstruction<ItemStack> scope = intercept()) {
            assertTrue(sell(seller, SPAWNER_KEY, 5).get("success").getAsBoolean());
        }

        verify(inventory).setItem(0, null);
        verify(inventory).setItem(4, null);
        // The third stack is the one only partly taken from.
        verify(third).setAmount(1);
        verify(inventory).setItem(9, third);
        verify(first, never()).setAmount(anyInt());
        verify(second, never()).setAmount(anyInt());
    }

    // --- reflection plumbing ------------------------------------------------

    private JsonObject sell(Player player, String itemKey, int amount) throws Exception {
        Method sell = WebPurchaseExecutor.class.getDeclaredMethod(
                "sell", Player.class, purchaseClass(), JsonObject.class);
        sell.setAccessible(true);
        JsonObject result = new JsonObject();
        sell.invoke(executor, player, purchase(itemKey, amount), result);
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
        Object sellType = Enum.valueOf((Class<Enum>) typeEnum.asSubclass(Enum.class), "SELL");
        return ctor.newInstance(
                "p1", sellType, UUID.randomUUID(), itemKey,
                0, 0, amount, 0L, "pending", null);
    }
}
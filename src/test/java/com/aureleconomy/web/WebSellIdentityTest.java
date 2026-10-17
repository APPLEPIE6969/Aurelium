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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
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
 * The web sell path may only take the item the entry names (H3b).
 *
 * <p>A named market entry shares its material with every other entry of the same
 * kind: all spawners are {@link Material#SPAWNER} and all enchanted books are
 * {@link Material#ENCHANTED_BOOK}. The old code resolved the key to a material
 * and then counted and removed stacks by {@code getType()} alone, so a player
 * holding one bare spawner could sell it as "Iron Golem Spawner" over and over
 * and be paid the iron golem spawner price each time. A pig spawner or a plain
 * enchanted book works the same way. This is the mirror of the buy-side bug,
 * where the player was charged correctly and given less, and it is worse because
 * the money leaves the server.
 *
 * <p>Every held stack here reports {@code SPAWNER} or {@code ENCHANTED_BOOK} from
 * {@code getType()}, exactly as a bare spawner and an iron golem spawner both do in
 * the real game, so only {@code isSimilar} can tell the pairs apart. That is the
 * whole point of the matcher and the reason a test that only checked the type
 * would have looked fine.
 *
 * <p>{@code sell} is private and its {@code Purchase} argument is package private
 * in another package, so both are reached reflectively, the same way
 * {@code ConfigMigrationTest} reaches {@code upgradeConfig}.
 *
 * <p>Stacks are intercepted with {@code mockConstruction} because
 * {@code new ItemStack} needs a live Bukkit registry, the same reason every other
 * test here mocks ItemStack.
 */
class WebSellIdentityTest {

    private static final String SPAWNER_KEY = "SPAWNER_IRON_GOLEM_SPAWNER";
    private static final String PLAIN_BOOK_KEY = "ENCHANTED_BOOK";
    private static final BigDecimal SPAWNER_SELL_PRICE = new BigDecimal("500000");
    private static final BigDecimal BOOK_SELL_PRICE = new BigDecimal("1800");
    private static final int SLOTS = 41;

    private AurelEconomy plugin;
    private MarketManager market;
    private EconomyManager economy;
    private Player seller;
    private PlayerInventory inventory;
    private ItemStack[] contents;
    private CreatureSpawner spawnerState;
    private WebPurchaseExecutor executor;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        market = mock(MarketManager.class);
        economy = mock(EconomyManager.class);

        Map<String, MarketEntry> cache = new HashMap<>();
        cache.put(SPAWNER_KEY, new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner"));
        cache.put(PLAIN_BOOK_KEY, new MarketEntry(Material.ENCHANTED_BOOK, 4_500));
        when(plugin.getMarketManager()).thenReturn(market);
        when(market.getEntryCache()).thenReturn(cache);
        when(market.getSellPrice(SPAWNER_KEY)).thenReturn(SPAWNER_SELL_PRICE);
        when(market.getCurrency(SPAWNER_KEY)).thenReturn("coins");
        when(market.getSellPrice(PLAIN_BOOK_KEY)).thenReturn(BOOK_SELL_PRICE);
        when(market.getCurrency(PLAIN_BOOK_KEY)).thenReturn("coins");

        when(plugin.getEconomyManager()).thenReturn(economy);
        when(economy.getFormattedWithSymbol(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.<BigDecimal>getArgument(0).toPlainString() + " coins");
        when(economy.getBalance(any(OfflinePlayer.class), anyString()))
                .thenReturn(BigDecimal.valueOf(500_000));
        // The payout succeeds unless a test says otherwise. Overridden by
        // failedPayoutLeavesTheItemsAlone.
        when(economy.deposit(any(OfflinePlayer.class), any(BigDecimal.class), anyString()))
                .thenReturn(true);

        seller = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        contents = new ItemStack[SLOTS];
        when(seller.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(contents);

        spawnerState = mock(CreatureSpawner.class);

        executor = new WebPurchaseExecutor(plugin, mock(PurchaseQueue.class));
    }

    /**
     * A held stack whose {@code getType()} is {@code type}, the way a bare spawner
     * and an iron golem spawner both report {@link Material#SPAWNER}.
     *
     * @param sameItem whether it really is the item the entry names, which is what
     *                 {@code isSimilar} answers and {@code getType} cannot
     */
    private ItemStack held(int slot, Material type, int amount, boolean sameItem) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.isSimilar(any(ItemStack.class))).thenReturn(sameItem);
        contents[slot] = stack;
        return stack;
    }

    /** A pig spawner: the same material as an iron golem spawner, a different item. */
    private ItemStack heldPigSpawner(int slot, int amount) {
        return held(slot, Material.SPAWNER, amount, false);
    }

    /** A bare spawner with no mob in it. */
    private ItemStack heldBareSpawner(int slot, int amount) {
        return held(slot, Material.SPAWNER, amount, false);
    }

    /** An enchanted book, offered against the plain book entry. */
    private ItemStack heldEnchantedBook(int slot, int amount) {
        return held(slot, Material.ENCHANTED_BOOK, amount, false);
    }

    /** Intercepts the factory-built stack for the entry being sold. */
    private MockedConstruction<ItemStack> intercept(Material type) {
        return mockConstruction(ItemStack.class, (stack, ctx) -> {
            when(stack.getType()).thenReturn(type);
            if (type == Material.SPAWNER) {
                BlockStateMeta meta = mock(BlockStateMeta.class);
                when(meta.getBlockState()).thenReturn(spawnerState);
                when(stack.getItemMeta()).thenReturn(meta);
            }
        });
    }

    // --- the vulnerability --------------------------------------------------

    @Test
    @DisplayName("a pig spawner offered against the iron golem entry is rejected, nothing removed, payout 0")
    void pigSpawnerAgainstIronGolemEntryIsRejected() throws Exception {
        heldPigSpawner(0, 1);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            result = sell(seller, SPAWNER_KEY, 1);

            assertNotNull(spawnerState, "the entry's real item should still be built");
            verify(spawnerState).setSpawnedType(EntityType.IRON_GOLEM);
        }

        assertFalse(result.has("success"), "a pig spawner was accepted as an iron golem spawner");
        assertTrue(result.has("error"));
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    @DisplayName("a bare spawner cannot be sold as an iron golem spawner")
    void bareSpawnerIsRefused() throws Exception {
        heldBareSpawner(0, 1);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            result = sell(seller, SPAWNER_KEY, 1);
        }

        assertFalse(result.has("success"), "a bare spawner was accepted as an iron golem spawner");
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    @DisplayName("an enchanted book is not sold against the plain book entry")
    void enchantedBookIsNotSoldAsItsPlainEntry() throws Exception {
        heldEnchantedBook(0, 1);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.ENCHANTED_BOOK)) {
            result = sell(seller, PLAIN_BOOK_KEY, 1);
        }

        assertFalse(result.has("success"),
                "an enchanted book was accepted against the plain book entry");
        assertTrue(result.has("error"));
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    @DisplayName("a bare spawner does not count towards an iron golem spawner sale")
    void bareSpawnerDoesNotCountTowardsTheTotal() throws Exception {
        heldPigSpawner(0, 1);
        held(1, Material.SPAWNER, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
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
        ItemStack pig = heldPigSpawner(0, 1);
        ItemStack golem = held(1, Material.SPAWNER, 2, true);

        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            assertTrue(sell(seller, SPAWNER_KEY, 2).get("success").getAsBoolean());
        }

        verify(inventory, never()).setItem(eq(0), any());
        verify(pig, never()).setAmount(anyInt());
        verify(inventory).setItem(1, null);
        verify(golem, never()).setAmount(anyInt());
    }

    @Test
    @DisplayName("an unknown key is refused before any payout")
    void unknownKeyIsRefused() throws Exception {
        held(0, Material.SPAWNER, 1, true);

        JsonObject result = sell(seller, "NOT_A_REAL_ITEM", 1);

        assertFalse(result.has("success"));
        verify(economy, never()).deposit(any(), any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("selling more than is held is refused and pays nothing")
    void notEnoughHeldIsRefused() throws Exception {
        held(0, Material.SPAWNER, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
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
        held(0, Material.SPAWNER, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            result = sell(seller, SPAWNER_KEY, 1);
        }

        assertTrue(result.get("success").getAsBoolean());
        assertEquals(1, result.get("amount").getAsInt(), "one spawner was sold");
        assertEquals("500000 coins", result.get("earned").getAsString());
        verify(economy).deposit(eq(seller), eq(SPAWNER_SELL_PRICE), eq("coins"));
        verify(inventory).setItem(0, null);
    }

    @Test
    @DisplayName("a real plain book sells and pays the plain entry price")
    void realPlainBookIsSold() throws Exception {
        held(0, Material.ENCHANTED_BOOK, 1, true);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.ENCHANTED_BOOK)) {
            result = sell(seller, PLAIN_BOOK_KEY, 1);
        }

        assertTrue(result.get("success").getAsBoolean());
        assertEquals("1800 coins", result.get("earned").getAsString());
        verify(economy).deposit(eq(seller), eq(BOOK_SELL_PRICE), eq("coins"));
        verify(inventory).setItem(0, null);
    }

    @Test
    @DisplayName("a failed payout leaves the seller's items alone")
    void failedPayoutLeavesTheItemsAlone() throws Exception {
        ItemStack golem = held(0, Material.SPAWNER, 1, true);
        when(economy.deposit(any(OfflinePlayer.class), any(BigDecimal.class), anyString()))
                .thenReturn(false);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            result = sell(seller, SPAWNER_KEY, 1);
        }

        assertFalse(result.has("success"), "a failed payout must not report a sale");
        assertTrue(result.has("error"));
        verify(inventory, never()).setItem(anyInt(), any());
        verify(golem, never()).setAmount(anyInt());
    }

    @Test
    @DisplayName("a refused withdrawal does not stand in for a refused payout")
    void refusedWithdrawalStillPaysOut() throws Exception {
        // A sale credits the seller, so it must not be gated on a withdrawal
        // result. This guards against someone "fixing" the order by making sell
        // refuse whenever any money call returns false.
        held(0, Material.SPAWNER, 1, true);
        when(economy.withdraw(any(OfflinePlayer.class), any(BigDecimal.class), anyString()))
                .thenReturn(false);

        JsonObject result;
        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            result = sell(seller, SPAWNER_KEY, 1);
        }

        assertTrue(result.get("success").getAsBoolean(),
                "a sale credits the seller and must not depend on a withdrawal");
        verify(economy).deposit(eq(seller), eq(SPAWNER_SELL_PRICE), eq("coins"));
    }

    @Test
    @DisplayName("only the sold amount is removed, leaving the rest of the stack")
    void partialRemovalLeavesTheRemainder() throws Exception {
        ItemStack golem = held(0, Material.SPAWNER, 5, true);

        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
            assertTrue(sell(seller, SPAWNER_KEY, 2).get("success").getAsBoolean());
        }

        verify(golem).setAmount(3);
        verify(inventory).setItem(0, golem);
        verify(economy).deposit(eq(seller), eq(new BigDecimal("1000000")), eq("coins"));
    }

    @Test
    @DisplayName("a sale spans several stacks and stops once the amount is reached")
    void saleSpansStacks() throws Exception {
        ItemStack first = held(0, Material.SPAWNER, 2, true);
        ItemStack second = held(4, Material.SPAWNER, 2, true);
        ItemStack third = held(9, Material.SPAWNER, 2, true);

        try (MockedConstruction<ItemStack> scope = intercept(Material.SPAWNER)) {
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
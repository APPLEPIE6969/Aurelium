package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.LedgerReason;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sell window's item identity and its live pricing (M2, M3).
 *
 * <p>Two defects met in one code path. The window guessed a price key from the
 * material name, so a spawner was looked up as {@code Iron Golem Spawner} while the
 * price map holds {@code SPAWNER_IRON_GOLEM_SPAWNER} and no spawner could ever be
 * sold. It also paid the total it had quoted when the screen opened, so a price
 * that moved before the player confirmed was ignored.
 *
 * <p>{@link MarketItemFactory#priceKey} is the item-to-key direction of the same
 * factory the web sell route uses, and {@link SellTransaction} quotes and pays at
 * confirm time. Stacks are mocked rather than built because {@code new ItemStack}
 * needs a live registry, the same reason every other test here mocks it.
 */
class SellKeyTest {

    private AurelEconomy plugin;
    private MarketManager market;
    private EconomyManager economy;
    private CustomItemRegistry registry;
    private Map<String, MarketEntry> cache;
    private OfflinePlayer seller;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        market = mock(MarketManager.class);
        economy = mock(EconomyManager.class);
        registry = mock(CustomItemRegistry.class);
        cache = new LinkedHashMap<>();
        seller = mock(OfflinePlayer.class);

        when(plugin.getMarketManager()).thenReturn(market);
        when(plugin.getEconomyManager()).thenReturn(economy);
        when(plugin.getCustomItemRegistry()).thenReturn(registry);
        when(registry.resolveItemId(any())).thenReturn(Optional.empty());
        when(market.getEntryCache()).thenReturn(cache);

        // The real key format, so the test would notice the GUI going back to a
        // display name: priceKey returns this value, not the map key it found.
        when(market.entryKey(any(MarketEntry.class))).thenAnswer(call -> {
            MarketEntry entry = call.getArgument(0);
            return entry.customName == null
                    ? entry.material.name()
                    : entry.material.name() + "_" + entry.customName.replace(" ", "_").toUpperCase();
        });
        when(economy.deposit(any(), any(), anyString(), any(), any(), any())).thenReturn(true);
    }

    // ------------------------------------------------------------------ M2

    @Test
    @DisplayName("a spawner is priced under its entry key and sells")
    void spawnerSellsAtItsOwnEntry() {
        cache.put("SPAWNER_IRON_GOLEM_SPAWNER",
                new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner"));
        when(market.getSellPrice("SPAWNER_IRON_GOLEM_SPAWNER")).thenReturn(BigDecimal.valueOf(375_000));
        when(market.getCurrency("SPAWNER_IRON_GOLEM_SPAWNER")).thenReturn("Aurels");

        ItemStack spawner = item(Material.SPAWNER, 1, mock(ItemMeta.class));
        BlockStateMeta referenceMeta = mock(BlockStateMeta.class);
        when(referenceMeta.getBlockState()).thenReturn(mock(CreatureSpawner.class));

        try (MockedConstruction<ItemStack> built = stackFactory(spawner, referenceMeta)) {
            String key = MarketItemFactory.priceKey(plugin, spawner);

            assertEquals("SPAWNER_IRON_GOLEM_SPAWNER", key,
                    "the sell window must use the price key, not the spawner's display name");
            assertTrue(market.getSellPrice(key).compareTo(BigDecimal.ZERO) > 0,
                    "a key that resolves must also have a price, or the spawner is still unsellable");

            List<Integer> cleared = new ArrayList<>();
            SellTransaction.Sale sale = new SellTransaction(plugin)
                    .sell(List.of(spawner), seller, cleared::add);

            assertEquals(List.of(0), cleared, "the sold spawner must leave the window");
            assertEquals(0, BigDecimal.valueOf(375_000).compareTo(sale.paid().get("Aurels")));
            verify(market).onTransaction("SPAWNER_IRON_GOLEM_SPAWNER", false, 1);
            verify(built.constructed().get(0)).isSimilar(spawner);
        }
    }

    @Test
    @DisplayName("a plain spawner is not sold as a named spawner")
    void plainSpawnerIsNotAnotherSpawner() {
        cache.put("SPAWNER_IRON_GOLEM_SPAWNER",
                new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner"));
        when(market.getSellPrice(anyString())).thenReturn(BigDecimal.valueOf(375_000));

        ItemStack pigSpawner = item(Material.SPAWNER, 1, mock(ItemMeta.class));
        BlockStateMeta referenceMeta = mock(BlockStateMeta.class);
        when(referenceMeta.getBlockState()).thenReturn(mock(CreatureSpawner.class));

        try (MockedConstruction<ItemStack> built = stackFactory(null, referenceMeta)) {
            assertNull(MarketItemFactory.priceKey(plugin, pigSpawner),
                    "a stack that is not the factory's item must not borrow its price");

            List<Integer> cleared = new ArrayList<>();
            SellTransaction.Sale sale = new SellTransaction(plugin)
                    .sell(List.of(pigSpawner), seller, cleared::add);

            assertTrue(cleared.isEmpty(), "a refused item must stay in the window");
            assertTrue(sale.isEmpty(), "a refused item must not pay out");
            assertEquals(1, sale.unsold());
            verify(economy, never()).deposit(any(), any(), anyString(), any(), any(), any());
        }
    }

    @Test
    @DisplayName("an enchanted item is refused rather than sold as its base material")
    void metaBearingItemIsNotSoldAsMaterial() {
        cache.put("DIAMOND_SWORD", new MarketEntry(Material.DIAMOND_SWORD, 500));
        when(market.getSellPrice(anyString())).thenReturn(BigDecimal.valueOf(250));

        ItemMeta meta = mock(ItemMeta.class);
        when(meta.hasEnchants()).thenReturn(true);
        ItemStack sword = item(Material.DIAMOND_SWORD, 1, meta);

        assertNull(MarketItemFactory.priceKey(plugin, sword),
                "selling an enchanted sword at the plain sword price destroys the enchantment");

        SellTransaction.Sale sale = new SellTransaction(plugin)
                .sell(List.of(sword), seller, slot -> {
                });
        assertTrue(sale.isEmpty());
        assertEquals(1, sale.unsold());
    }

    @Test
    @DisplayName("a plain stack still sells at its material price")
    void plainStackSellsAtMaterialPrice() {
        cache.put("DIAMOND", new MarketEntry(Material.DIAMOND, 100));
        when(market.getSellPrice("DIAMOND")).thenReturn(BigDecimal.valueOf(50));
        when(market.getCurrency("DIAMOND")).thenReturn("Aurels");

        ItemStack diamond = item(Material.DIAMOND, 3, mock(ItemMeta.class));

        assertEquals("DIAMOND", MarketItemFactory.priceKey(plugin, diamond));

        SellTransaction.Quote quote = new SellTransaction(plugin).quote(List.of(diamond));
        assertEquals(1, quote.lines().size());
        assertEquals(0, BigDecimal.valueOf(150).compareTo(quote.totals().get("Aurels")),
                "the price is per item, the payout is for the stack");
    }

    @Test
    @DisplayName("a registered custom item sells as itself, a disabled one not at all")
    void customItemSellsByCanonicalId() {
        CustomMarketItem custom = mock(CustomMarketItem.class);
        when(custom.isEnabled()).thenReturn(true);
        when(registry.resolveItemId(any())).thenReturn(Optional.of("itemsadder:ruby"));
        when(registry.getById("itemsadder:ruby")).thenReturn(custom);
        cache.put("itemsadder:ruby", new MarketEntry(Material.DIAMOND, 500, "Ruby"));

        ItemStack ruby = item(Material.DIAMOND, 1, mock(ItemMeta.class));
        assertEquals("itemsadder:ruby", MarketItemFactory.priceKey(plugin, ruby));

        when(custom.isEnabled()).thenReturn(false);
        assertNull(MarketItemFactory.priceKey(plugin, ruby),
                "a switched-off custom item must not fall back to its material price");
    }

    @Test
    @DisplayName("the shared matcher counts and removes the factory item, not the material")
    void sharedMatcherIgnoresOtherSpawners() {
        ItemStack reference = mock(ItemStack.class);
        ItemStack iron = mock(ItemStack.class);
        when(iron.isSimilar(reference)).thenReturn(true);
        when(iron.getAmount()).thenReturn(2);
        ItemStack pig = mock(ItemStack.class);
        when(pig.isSimilar(reference)).thenReturn(false);
        when(pig.getAmount()).thenReturn(5);

        Inventory inventory = mock(Inventory.class);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[] { pig, iron });

        assertEquals(2, MarketItemFactory.countSimilar(inventory, reference, 10));

        MarketItemFactory.removeSimilar(inventory, reference, 2);
        verify(inventory).setItem(1, null);
        verify(inventory, never()).setItem(0, null);
    }

    // ------------------------------------------------------------------ M3

    @Test
    @DisplayName("the payout uses the price at confirm time, not the quote at open")
    void payoutUsesThePriceAtConfirmTime() {
        cache.put("DIAMOND", new MarketEntry(Material.DIAMOND, 100));
        when(market.getSellPrice("DIAMOND")).thenReturn(BigDecimal.valueOf(100));
        when(market.getCurrency("DIAMOND")).thenReturn("Aurels");
        ItemStack diamond = item(Material.DIAMOND, 1, mock(ItemMeta.class));

        SellTransaction transaction = new SellTransaction(plugin);
        SellTransaction.Quote atOpen = transaction.quote(List.of(diamond));
        assertEquals(0, BigDecimal.valueOf(100).compareTo(atOpen.totals().get("Aurels")));

        // The market moves while the confirm screen is open.
        when(market.getSellPrice("DIAMOND")).thenReturn(BigDecimal.valueOf(200));

        List<Integer> cleared = new ArrayList<>();
        SellTransaction.Sale sale = transaction.sell(List.of(diamond), seller, cleared::add);

        assertEquals(List.of(0), cleared);
        assertEquals(0, BigDecimal.valueOf(200).compareTo(sale.paid().get("Aurels")),
                "paying the opening quote means the price move was ignored");
        verify(economy).deposit(seller, BigDecimal.valueOf(200), "Aurels",
                LedgerReason.MARKET_SELL, null, null);
    }

    @Test
    @DisplayName("a failed payout takes nothing from the window")
    void failedPayoutLeavesTheItems() {
        cache.put("DIAMOND", new MarketEntry(Material.DIAMOND, 100));
        when(market.getSellPrice("DIAMOND")).thenReturn(BigDecimal.valueOf(50));
        when(market.getCurrency("DIAMOND")).thenReturn("Aurels");
        when(economy.deposit(any(), any(), anyString(), any(), any(), any())).thenReturn(false);

        ItemStack diamond = item(Material.DIAMOND, 1, mock(ItemMeta.class));
        List<Integer> cleared = new ArrayList<>();

        SellTransaction.Sale sale = new SellTransaction(plugin)
                .sell(List.of(diamond), seller, cleared::add);

        assertTrue(cleared.isEmpty(), "an item must not be taken without a completed payout");
        assertTrue(sale.isEmpty());
        assertEquals(1, sale.unsold());
        verify(market, never()).onTransaction(anyString(), anyBoolean(), anyInt());
    }

    // --------------------------------------------------------------- helpers

    private static ItemStack item(Material material, int amount, ItemMeta meta) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(amount);
        when(stack.getItemMeta()).thenReturn(meta);
        return stack;
    }

    /**
     * Intercepts the reference stack the factory builds. It is similar only to
     * {@code subject}, so a different stack of the same material cannot match.
     */
    private static MockedConstruction<ItemStack> stackFactory(ItemStack subject, ItemMeta meta) {
        return mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(meta);
            when(stack.getAmount()).thenReturn(1);
            when(stack.isSimilar(any(ItemStack.class)))
                    .thenAnswer(call -> call.getArgument(0) == subject);
        });
    }
}

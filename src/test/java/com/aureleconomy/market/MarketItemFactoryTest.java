package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.aureleconomy.market.MarketItems.MarketEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the item construction a market entry implies (H3).
 *
 * <p>A named entry is not interchangeable with a plain stack of its material.
 * Every spawner is {@link Material#SPAWNER}, so the mob lives in the block state;
 * every enchanted book is {@link Material#ENCHANTED_BOOK}, so the enchant lives
 * in the meta. Both web purchase paths used to hand out a bare stack while
 * charging the custom price, so a player buying Protection II received an empty
 * book and a player buying an iron golem spawner received an empty spawner.
 *
 * <p>Stacks are intercepted with {@code mockConstruction} rather than really
 * built, because {@code new ItemStack} needs a live Bukkit registry and this
 * suite has no server. That is the same reason every other test here mocks
 * ItemStack. It is also why the enchantment <em>parsing</em> is not retested
 * here: {@code MarketItems.createEnchantedBook} is pre-existing code reached
 * through this factory, and parsing it needs real enchantments. What is
 * asserted is that the factory reaches it at all with the entry's stored name,
 * which is precisely what the old web paths never did.
 */
class MarketItemFactoryTest {

    private static AurelEconomy plugin() {
        AurelEconomy plugin = mock(AurelEconomy.class);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        return plugin;
    }

    /**
     * Intercepts every ItemStack built while it is open and records the
     * constructor arguments, which is how the material is asserted without a
     * real stack to read it off.
     */
    private static final class Stacks {
        final List<List<?>> args = new ArrayList<>();
        MockedConstruction<ItemStack> scope;

        MockedConstruction<ItemStack> intercept(ItemMeta meta) {
            scope = Mockito.mockConstruction(ItemStack.class, (stack, ctx) -> {
                args.add(ctx.arguments());
                if (meta != null) {
                    when(stack.getItemMeta()).thenReturn(meta);
                }
            });
            return scope;
        }

        ItemStack first() {
            assertFalse(scope.constructed().isEmpty(), "no stack was built");
            return scope.constructed().get(0);
        }

        Object arg(int construction, int position) {
            assertTrue(construction < args.size(),
                    "expected at least " + (construction + 1) + " stacks, built " + args.size());
            List<?> list = args.get(construction);
            assertTrue(position < list.size(),
                    "expected at least " + (position + 1) + " constructor arguments, had " + list.size());
            return list.get(position);
        }
    }

    /** A spawner stack plus the block state the factory writes into. */
    private static final class Spawner {
        final CreatureSpawner state = mock(CreatureSpawner.class);
        final BlockStateMeta meta = mock(BlockStateMeta.class);
        final Stacks stacks = new Stacks();

        Spawner() {
            when(meta.getBlockState()).thenReturn(state);
        }

        MockedConstruction<ItemStack> intercept() {
            return stacks.intercept(meta);
        }
    }

    // --- spawners -----------------------------------------------------------

    @Test
    @DisplayName("a named spawner entry writes its mob type and name, not a bare stack")
    void spawnerCarriesMobTypeAndName() {
        Spawner spawner = new Spawner();
        MarketEntry entry = new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner");

        try (MockedConstruction<ItemStack> scope = spawner.intercept()) {
            ItemStack stack = MarketItemFactory.build(plugin(), entry);

            assertEquals(Material.SPAWNER, spawner.stacks.arg(0, 0));
            assertEquals(1, spawner.stacks.args.size(),
                    "a spawner entry must be built exactly once");
            assertSame(spawner.stacks.first(), stack,
                    "the factory must hand back the stack it built");

            verify(spawner.state).setSpawnedType(EntityType.IRON_GOLEM);
            verify(spawner.meta).setBlockState(spawner.state);
            verify(spawner.meta).displayName(
                    Component.text("Iron Golem Spawner", NamedTextColor.AQUA));
            verify(stack).setItemMeta(spawner.meta);
        }
    }

    @Test
    @DisplayName("every spawner entry in the market names a real mob")
    void everySpawnerEntryNamesARealMob() {
        List<MarketEntry> spawners = MarketItems.getItems(MarketItems.Category.SPAWNERS).stream()
                .filter(e -> e.material == Material.SPAWNER && e.customName != null)
                .toList();
        assertTrue(spawners.size() > 50,
                "expected the spawner category to hold the spawners, found " + spawners.size());

        Spawner spawner = new Spawner();
        try (MockedConstruction<ItemStack> scope = spawner.intercept()) {
            for (MarketEntry entry : spawners) {
                MarketItemFactory.build(plugin(), entry);
            }

            // A display name that is not a mob is caught and skipped, so the count
            // of real mob writes must cover every entry or someone paid for an
            // empty spawner.
            ArgumentCaptor<EntityType> written = ArgumentCaptor.forClass(EntityType.class);
            verify(spawner.state, times(spawners.size())).setSpawnedType(written.capture());

            for (int i = 0; i < spawners.size(); i++) {
                String name = spawners.get(i).customName;
                String mob = name.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                assertEquals(mob, written.getAllValues().get(i).name(),
                        "spawner entry was written with the wrong mob: " + name);
            }
        }
    }

    @Test
    @DisplayName("a spawner name that is not a mob still yields a named spawner, not a crash")
    void unknownSpawnerNameDegrades() {
        Spawner spawner = new Spawner();
        MarketEntry entry = new MarketEntry(Material.SPAWNER, 100, "Not A Real Mob Spawner");

        try (MockedConstruction<ItemStack> scope = spawner.intercept()) {
            ItemStack stack = MarketItemFactory.build(plugin(), entry);

            assertNotNull(stack);
            assertEquals(Material.SPAWNER, spawner.stacks.arg(0, 0));
            verify(spawner.state, never()).setSpawnedType(any(EntityType.class));
            // The name is still applied so the player can see what they bought.
            verify(spawner.meta).displayName(
                    Component.text("Not A Real Mob Spawner", NamedTextColor.AQUA));
        }
    }

    @Test
    @DisplayName("a spawner built with an amount still gets the mob type")
    void spawnerWithAmount() {
        Spawner spawner = new Spawner();
        MarketEntry entry = new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner");

        try (MockedConstruction<ItemStack> scope = spawner.intercept()) {
            ItemStack stack = MarketItemFactory.build(plugin(), entry, 8);

            verify(stack).setAmount(8);
            verify(spawner.state).setSpawnedType(EntityType.IRON_GOLEM);
        }
    }

    // --- enchanted books ----------------------------------------------------

    @Test
    @DisplayName("a named book entry is built from its stored name, not a plain book")
    void enchantedBookIsBuiltFromItsName() {
        MarketEntry entry = new MarketEntry(Material.ENCHANTED_BOOK, 4_500, "Protection II");
        ItemStack book = mock(ItemStack.class);

        try (MockedStatic<MarketItems> statics = Mockito.mockStatic(MarketItems.class)) {
            statics.when(() -> MarketItems.createEnchantedBook("Protection II")).thenReturn(book);

            ItemStack stack = MarketItemFactory.build(plugin(), entry);

            assertSame(book, stack,
                    "the factory must hand back the built book, not an empty one");
            statics.verify(() -> MarketItems.createEnchantedBook("Protection II"), times(1));
        }
    }

    @Test
    @DisplayName("an unnamed book entry is a plain stack")
    void unnamedBookIsPlain() {
        MarketEntry entry = new MarketEntry(Material.ENCHANTED_BOOK, 4_500);
        Stacks stacks = new Stacks();

        try (MockedConstruction<ItemStack> scope = stacks.intercept(null)) {
            ItemStack stack = MarketItemFactory.build(plugin(), entry);

            assertNotNull(stack);
            assertEquals(Material.ENCHANTED_BOOK, stacks.arg(0, 0));
        }
    }

    @Test
    @DisplayName("every named book entry is routed through the book builder")
    void everyNamedBookRoutesThroughTheBuilder() {
        List<String> names = MarketItems.getItems(MarketItems.Category.ENCHANTMENTS).stream()
                .filter(e -> e.material == Material.ENCHANTED_BOOK && e.customName != null)
                .map(e -> e.customName)
                .toList();
        assertTrue(names.size() > 100,
                "expected the enchantment category to hold the books, found " + names.size());

        try (MockedStatic<MarketItems> statics = Mockito.mockStatic(MarketItems.class)) {
            for (String name : names) {
                MarketItemFactory.build(plugin(),
                        new MarketEntry(Material.ENCHANTED_BOOK, 100, name));
            }
            for (String name : names) {
                statics.verify(() -> MarketItems.createEnchantedBook(name), times(1));
            }
        }
    }

    // --- plain items --------------------------------------------------------

    @Test
    @DisplayName("a plain entry yields a plain stack of its material")
    void plainEntryIsPlain() {
        MarketEntry entry = new MarketEntry(Material.DIAMOND, 500);
        Stacks stacks = new Stacks();

        try (MockedConstruction<ItemStack> scope = stacks.intercept(null)) {
            ItemStack stack = MarketItemFactory.build(plugin(), entry);

            assertEquals(Material.DIAMOND, stacks.arg(0, 0));
            assertEquals(1, stacks.args.size());
            verify(stack, never()).getItemMeta();
        }
    }

    @Test
    @DisplayName("amount is applied and the material is unchanged")
    void amountIsApplied() {
        MarketEntry entry = new MarketEntry(Material.DIAMOND, 500);
        Stacks stacks = new Stacks();

        try (MockedConstruction<ItemStack> scope = stacks.intercept(null)) {
            ItemStack stack = MarketItemFactory.build(plugin(), entry, 64);

            assertEquals(Material.DIAMOND, stacks.arg(0, 0));
            verify(stack).setAmount(64);
        }
    }

    // --- key resolution -----------------------------------------------------

    @Test
    @DisplayName("a null entry yields null rather than a wrong item")
    void nullEntryIsNull() {
        assertNull(MarketItemFactory.build(plugin(), null));
        assertNull(MarketItemFactory.build(plugin(), null, 5));
        assertNull(MarketItemFactory.buildForKey(plugin(), null, 1));
    }

    @Test
    @DisplayName("a named key resolves through the entry cache and builds the real item")
    void namedKeyResolvesThroughCache() {
        AurelEconomy plugin = plugin();
        MarketManager manager = mock(MarketManager.class);
        MarketEntry entry = new MarketEntry(Material.SPAWNER, 750_000, "Iron Golem Spawner");
        Map<String, MarketEntry> cache = new HashMap<>();
        cache.put("SPAWNER_IRON_GOLEM_SPAWNER", entry);
        when(plugin.getMarketManager()).thenReturn(manager);
        when(manager.getEntryCache()).thenReturn(cache);

        Spawner spawner = new Spawner();
        try (MockedConstruction<ItemStack> scope = spawner.intercept()) {
            ItemStack stack = MarketItemFactory.buildForKey(plugin, "SPAWNER_IRON_GOLEM_SPAWNER", 2);

            assertNotNull(stack);
            verify(stack).setAmount(2);
            verify(spawner.state).setSpawnedType(EntityType.IRON_GOLEM);
        }
    }

    @Test
    @DisplayName("a key in the cache that is not a named entry still builds its material")
    void cachedPlainKeyResolves() {
        AurelEconomy plugin = plugin();
        MarketManager manager = mock(MarketManager.class);
        MarketEntry entry = new MarketEntry(Material.DIAMOND, 500);
        Map<String, MarketEntry> cache = new HashMap<>();
        cache.put("DIAMOND", entry);
        when(plugin.getMarketManager()).thenReturn(manager);
        when(manager.getEntryCache()).thenReturn(cache);

        Stacks stacks = new Stacks();
        try (MockedConstruction<ItemStack> scope = stacks.intercept(null)) {
            ItemStack stack = MarketItemFactory.buildForKey(plugin, "DIAMOND", 5);

            assertEquals(Material.DIAMOND, stacks.arg(0, 0));
            verify(stack).setAmount(5);
        }
    }

    @Test
    @DisplayName("a key resolving to nothing yields null so the caller refuses, not substitutes")
    void unknownKeyIsNull() {
        AurelEconomy plugin = plugin();
        MarketManager manager = mock(MarketManager.class);
        when(plugin.getMarketManager()).thenReturn(manager);
        when(manager.getEntryCache()).thenReturn(new HashMap<>());

        assertNull(MarketItemFactory.entryForKey(plugin, "NOT_A_REAL_ITEM"),
                "an unknown key must not resolve to some other entry");
        assertNull(MarketItemFactory.buildForKey(plugin, "NOT_A_REAL_ITEM", 1),
                "an unknown key must refuse rather than hand over some other item");
        assertNull(MarketItemFactory.buildForKey(null, "DIAMOND", 1));
    }
}
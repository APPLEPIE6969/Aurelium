package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The auction house rebuilds its screens once a second, and the collection bin used
 * to read its rows on every one of those rebuilds (M6).
 *
 * <p>The bin is rendered from a snapshot now. These tests drive the real
 * {@link AuctionManager} against a database that refuses to be used once the test
 * arms it, so a rebuild that still queries does not go unnoticed: it fails instead
 * of quietly costing a query a second.
 */
class AuctionBinSnapshotTest {

    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> stacks;
    private final List<Runnable> asyncTasks = new ArrayList<>();
    private final List<Runnable> mainTasks = new ArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicBoolean armed = new AtomicBoolean();

    private AurelEconomy plugin;
    private Player player;
    private Inventory inventory;
    private AuctionManager auctions;
    private final UUID playerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(mock(ItemMeta.class));
            when(stack.getAmount()).thenReturn(1);
        });

        inventory = mock(Inventory.class);
        when(inventory.firstEmpty()).thenReturn(0);

        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(scheduler.runTaskAsynchronously(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    asyncTasks.add(call.getArgument(1));
                    return mock(BukkitTask.class);
                });
        when(scheduler.runTask(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    Runnable task = call.getArgument(1);
                    mainTasks.add(task);
                    task.run();
                    return mock(BukkitTask.class);
                });
        // The expiry sweep is not part of this test, so it never fires here.
        when(scheduler.runTaskTimer(any(Plugin.class), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class)))
                .thenReturn(inventory);

        DatabaseManager database = mock(DatabaseManager.class);
        // Until the test arms it the database is merely unavailable, which is how
        // AuctionManager loads an empty auction list at construction.
        when(database.getConnection()).thenAnswer(call -> {
            connections.incrementAndGet();
            if (armed.get())
                throw new AssertionError("the GUI refresh path opened a database connection");
            throw new SQLException("no database in this test");
        });

        EconomyManager economy = mock(EconomyManager.class);
        when(economy.getDefaultCurrency()).thenReturn("Aurelium");

        plugin = mock(AurelEconomy.class);
        when(plugin.namespace()).thenReturn("aurelium");
        when(plugin.getName()).thenReturn("Aurelium");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        when(plugin.getDatabaseManager()).thenReturn(database);
        when(plugin.getEconomyManager()).thenReturn(economy);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getOpenInventory()).thenReturn(null);

        auctions = new AuctionManager(plugin);
        when(plugin.getAuctionManager()).thenReturn(auctions);

        // Construction read the auction list once; from here on a connection is a
        // rebuild that still queries.
        connections.set(0);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        stacks.close();
    }

    @Test
    @DisplayName("the first render asks for one background read instead of querying")
    void firstRenderLoadsOffThread() {
        new AuctionGUI(plugin, player, true);

        assertEquals(1, asyncTasks.size(), "opening the bin must schedule exactly one read");
        assertEquals(0, connections.get(), "the main thread must not open the connection itself");

        asyncTasks.remove(0).run();

        assertNotNull(auctions.getCachedCollectionBin(playerId),
                "the read must leave a snapshot behind for the next render");
        assertEquals(1, connections.get(), "the read itself is the one place that may touch the database");
    }

    @Test
    @DisplayName("a second request while the first is in flight is dropped, not queued")
    void oneReadInFlightPerPlayer() {
        new AuctionGUI(plugin, player, true);
        new AuctionGUI(plugin, player, true);

        assertEquals(1, asyncTasks.size(),
                "a rebuild during the read must not add another: the one running answers for both");
    }

    @Test
    @DisplayName("sixty refresh ticks read nothing and allocate nothing")
    void sixtyRefreshTicksDoNotQueryOrGrow() {
        new AuctionGUI(plugin, player, true);
        asyncTasks.remove(0).run();

        List<AuctionItem> snapshot = auctions.getCachedCollectionBin(playerId);
        int connectionsAfterWarmRead = connections.get();
        armed.set(true);

        AuctionGUI gui = new AuctionGUI(plugin, player, true);
        int tasksAfterConstruction = asyncTasks.size();
        for (int tick = 0; tick < 60; tick++) {
            gui.refresh();
        }

        assertEquals(connectionsAfterWarmRead, connections.get(),
                "a refresh tick must never open a database connection");
        assertEquals(tasksAfterConstruction, asyncTasks.size(),
                "ticks schedule no reads of their own, so an hour of ticks adds no work");
        assertSame(snapshot, auctions.getCachedCollectionBin(playerId),
                "the snapshot is reused across ticks rather than rebuilt each second");
    }

    @Test
    @DisplayName("invalidating a snapshot makes the next render read again")
    void invalidationForcesARead() {
        new AuctionGUI(plugin, player, true);
        asyncTasks.remove(0).run();

        auctions.invalidateCollectionBin(playerId);
        assertNull(auctions.getCachedCollectionBin(playerId),
                "an invalidated snapshot must not be rendered as if it were current");

        new AuctionGUI(plugin, player, true);
        assertEquals(1, asyncTasks.size(), "the next render reads the bin again");

        asyncTasks.remove(0).run();
        assertNotNull(auctions.getCachedCollectionBin(playerId));
    }

    @Test
    @DisplayName("the read hands its result back on the main thread")
    void resultReturnsToTheMainThread() {
        List<List<AuctionItem>> delivered = new ArrayList<>();
        auctions.refreshCollectionBin(playerId, delivered::add);

        assertEquals(0, delivered.size(), "nothing is handed back before the read runs");

        asyncTasks.remove(0).run();

        assertEquals(1, delivered.size(), "the callback runs once, on the main thread");
        assertEquals(1, mainTasks.size());
        assertSame(auctions.getCachedCollectionBin(playerId), delivered.get(0),
                "the callback receives the snapshot that is now cached");
    }
}

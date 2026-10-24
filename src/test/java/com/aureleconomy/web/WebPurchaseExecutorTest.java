package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.orders.BuyOrder;
import com.aureleconomy.orders.OrderManager;
import com.aureleconomy.webstore.InMemoryPurchaseQueue;
import com.aureleconomy.webstore.PurchaseQueue;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H10/M7: a fill-order is dispatched to {@code OrderManager} and completes
 * asynchronously, so the executor must leave it PROCESSING until the order's
 * persisted fill counter moves or the attempt window expires. The old code
 * answered "failed" from the same pass, then overwrote the row afterwards
 * because {@code complete} had no status guard.
 */
class WebPurchaseExecutorTest {

    private static final UUID FILLER = UUID.randomUUID();
    private static final UUID BUYER = UUID.randomUUID();

    private AurelEconomy plugin;
    private OrderManager orderManager;
    private InMemoryPurchaseQueue queue;
    private Player player;
    private BuyOrder order;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        orderManager = mock(OrderManager.class);
        queue = new InMemoryPurchaseQueue(15 * 60_000L);
        when(plugin.getOrderManager()).thenReturn(orderManager);

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(FILLER);
        when(player.isOnline()).thenReturn(true);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        when(stack.getAmount()).thenReturn(5);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{stack});

        order = new BuyOrder(7, BUYER, Material.DIAMOND, 10, 0,
                BigDecimal.ONE, "Aurels", "ACTIVE");
        when(orderManager.getActiveOrders()).thenReturn(List.of(order));
    }

    @Test
    @DisplayName("a dispatched fill stays processing until the order counter moves")
    void dispatchedFillSettlesOnlyAfterTheOrderMoves() {
        String id = queue.enqueue(PurchaseQueue.Type.FILL_ORDER, FILLER, null, 0, 7, 3);
        WebPurchaseExecutor executor = new WebPurchaseExecutor(plugin, queue, 15_000L);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

            executor.drain();
            assertEquals(PurchaseQueue.PROCESSING, queue.find(id).status(),
                    "the executor settled the purchase before the fill had run");
            verify(orderManager).fillOrder(player, 7, 3);

            order.setAmountFilled(3);
            executor.drain();
            PurchaseQueue.Purchase settled = queue.find(id);
            assertEquals(PurchaseQueue.COMPLETED, settled.status());
            assertTrue(settled.resultJson().contains("\"filled\":3"), settled.resultJson());

            executor.drain();
            PurchaseQueue.Purchase again = queue.find(id);
            assertEquals(PurchaseQueue.COMPLETED, again.status());
            assertTrue(again.resultJson().contains("\"filled\":3"),
                    "a second pass replaced the first result");
        }
    }

    @Test
    @DisplayName("a fill that never moves the order fails once the attempt window expires")
    void unresolvedFillFailsOnce() throws Exception {
        String id = queue.enqueue(PurchaseQueue.Type.FILL_ORDER, FILLER, null, 0, 7, 3);
        WebPurchaseExecutor executor = new WebPurchaseExecutor(plugin, queue, 0L);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));

            executor.drain();
            assertEquals(PurchaseQueue.PROCESSING, queue.find(id).status());

            Thread.sleep(5L);
            executor.drain();
            PurchaseQueue.Purchase failed = queue.find(id);
            assertEquals(PurchaseQueue.FAILED, failed.status());
            assertTrue(failed.resultJson().contains("could not be filled"), failed.resultJson());

            order.setAmountFilled(3);
            executor.drain();
            assertEquals(PurchaseQueue.FAILED, queue.find(id).status(),
                    "a late fill result overwrote the failure");
        }
    }

    @Test
    @DisplayName("a player who went offline before execution is cancelled, not executed")
    void offlinePlayerIsNotExecuted() {
        when(player.isOnline()).thenReturn(false);
        String id = queue.enqueue(PurchaseQueue.Type.FILL_ORDER, FILLER, null, 0, 7, 3);
        WebPurchaseExecutor executor = new WebPurchaseExecutor(plugin, queue, 15_000L);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            executor.drain();
        }

        assertEquals(PurchaseQueue.FAILED, queue.find(id).status());
        verify(orderManager, never()).fillOrder(any(), anyInt(), anyInt());
    }
}

package com.aureleconomy.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.webstore.PurchaseQueue;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * H4's required test: a BIN purchase from the dashboard buys the quantity it
 * asks for, not the price it happens to show.
 *
 * <p>The dashboard used to send the unit price in {@code amount}, and this
 * handler stored that field as the purchase quantity. A listing priced 100.50
 * per unit therefore queued a purchase of 100 units for 10,050: the browser
 * showed a one-click price and the plugin tried to spend a hundred times it.
 * Nothing in the path capped the number, and the executor used it as the
 * quantity as well.
 *
 * <p>The contract this test pins down:
 *
 * <ul>
 *   <li>a BIN request without {@code quantity} is refused, so the legacy
 *       amount-as-quantity request cannot be honoured by accident;</li>
 *   <li>{@code quantity: 1} queues exactly one unit, whatever the price;</li>
 *   <li>a quantity larger than the remaining stock is capped at the stock;</li>
 *   <li>a real bid still carries its price in {@code amount} and is unchanged.</li>
 * </ul>
 *
 * <p>{@code enqueue} is private and the request is an {@link HttpExchange}, so
 * both are reached the way {@link WebBuyChargeTest} reaches {@code buy}: by
 * reflection, with the exchange's request and response streams stubbed.
 */
class DashboardApiHandlerBidTest {

    private static final String CURRENCY = "coins";
    private static final BigDecimal UNIT_PRICE = new BigDecimal("100.50");
    private static final int AUCTION_ID = 7;

    private AurelEconomy plugin;
    private AuctionManager auctions;
    private PurchaseQueue queue;
    private DashboardApiHandler handler;
    private UUID player;

    @BeforeEach
    void setUp() {
        plugin = mock(AurelEconomy.class);
        auctions = mock(AuctionManager.class);
        queue = mock(PurchaseQueue.class);
        when(plugin.getAuctionManager()).thenReturn(auctions);
        when(queue.enqueue(any(), any(), any(), anyInt(), anyInt(), anyInt())).thenReturn("p1");

        handler = new DashboardApiHandler(plugin, mock(WebSessionManager.class),
                mock(com.aureleconomy.webstore.WebSnapshotCache.class), queue, "test");
        player = UUID.randomUUID();
    }

    // --- the contract -------------------------------------------------------

    @Test
    @DisplayName("a BIN purchase without a quantity is refused instead of reading the price as one")
    void binWithoutQuantityIsRefused() throws Exception {
        stubBin(PurchaseMode.UNIT, 5);

        Response response = bid("{\"auctionId\":" + AUCTION_ID + ",\"amount\":100.50}");

        assertEquals(400, response.status, "the legacy amount-as-quantity request was accepted");
        verify(queue, never()).enqueue(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("quantity 1 on a 100.50 BIN listing queues one unit, not one hundred")
    void quantityOneQueuesOneUnit() throws Exception {
        stubBin(PurchaseMode.UNIT, 5);

        Response response = bid("{\"auctionId\":" + AUCTION_ID + ",\"quantity\":1}");

        assertEquals(200, response.status);
        assertEquals(1, queuedAmount(), "the unit price leaked back in as a quantity");
        assertTrue(response.body.contains("purchaseId"), "the browser needs an id to poll: " + response.body);
    }

    @Test
    @DisplayName("a quantity larger than the remaining stock is capped at the stock")
    void quantityIsCappedAtStock() throws Exception {
        stubBin(PurchaseMode.UNIT, 2);

        Response response = bid("{\"auctionId\":" + AUCTION_ID + ",\"quantity\":3}");

        assertEquals(200, response.status);
        assertEquals(2, queuedAmount(), "the queue was told to buy more than the listing holds");
    }

    @Test
    @DisplayName("a fractional quantity is refused rather than floored")
    void fractionalQuantityIsRefused() throws Exception {
        stubBin(PurchaseMode.UNIT, 5);

        Response response = bid("{\"auctionId\":" + AUCTION_ID + ",\"quantity\":1.5}");

        assertEquals(400, response.status);
        verify(queue, never()).enqueue(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("a real bid still sends its price in amount")
    void bidPriceIsUnchanged() throws Exception {
        stubAuction();

        Response response = bid("{\"auctionId\":" + AUCTION_ID + ",\"amount\":150.25}");

        assertEquals(200, response.status);
        assertEquals(150, queuedAmount(), "a bid's amount is a price, and it must still reach the queue");
    }

    @Test
    @DisplayName("a bid with no price is still refused")
    void bidWithoutPriceIsRefused() throws Exception {
        stubAuction();

        Response response = bid("{\"auctionId\":" + AUCTION_ID + ",\"quantity\":1}");

        assertEquals(400, response.status);
        verify(queue, never()).enqueue(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    // --- plumbing -----------------------------------------------------------

    private enum PurchaseMode { UNIT, STACK }

    private void stubBin(PurchaseMode mode, int stock) {
        // Build the listing before starting the stubbing: constructing it stubs
        // the item mock, and a nested when() inside an unfinished when() is
        // exactly what Mockito refuses.
        AuctionItem bin = listing(true, mode, stock);
        when(auctions.getAuctionById(AUCTION_ID)).thenReturn(bin);
    }

    private void stubAuction() {
        AuctionItem auction = listing(false, PurchaseMode.STACK, 1);
        when(auctions.getAuctionById(AUCTION_ID)).thenReturn(auction);
    }

    private static AuctionItem listing(boolean isBin, PurchaseMode mode, int stock) {
        // ItemStack needs no construction here: the only thing read from it is
        // the amount, which is where a UNIT listing keeps its remaining stock.
        ItemStack item = mock(ItemStack.class);
        when(item.getAmount()).thenReturn(stock);
        return new AuctionItem.Builder()
                .id(AUCTION_ID)
                .seller(UUID.randomUUID())
                .item(item)
                .price(UNIT_PRICE)
                .currency(CURRENCY)
                .isBin(isBin)
                .expiration(System.currentTimeMillis() + 60_000L)
                .startTime(System.currentTimeMillis())
                .purchaseMode(mode == PurchaseMode.UNIT
                        ? AuctionItem.PurchaseMode.UNIT : AuctionItem.PurchaseMode.STACK)
                .build();
    }

    private int queuedAmount() throws Exception {
        ArgumentCaptor<Integer> amount = ArgumentCaptor.forClass(Integer.class);
        verify(queue).enqueue(any(), any(), any(), anyInt(), anyInt(), amount.capture());
        return amount.getValue();
    }

    private Response bid(String body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getRequestBody())
                .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(out);

        Method enqueue = DashboardApiHandler.class.getDeclaredMethod(
                "enqueue", HttpExchange.class, PurchaseQueue.Type.class, UUID.class);
        enqueue.setAccessible(true);
        enqueue.invoke(handler, exchange, PurchaseQueue.Type.BID, player);

        int status = statusOf(exchange);
        return new Response(status, out.toString(StandardCharsets.UTF_8));
    }

    private static int statusOf(HttpExchange exchange) throws Exception {
        ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
        verify(exchange).sendResponseHeaders(status.capture(), anyLong());
        return status.getValue();
    }

    private record Response(int status, String body) { }
}

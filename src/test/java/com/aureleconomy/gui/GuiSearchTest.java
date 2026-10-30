package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.market.MarketManager;
import com.aureleconomy.orders.BuyOrder;
import com.aureleconomy.orders.OrderManager;
import com.aureleconomy.utils.ChatPromptManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The search state shared by the menu screens (M1).
 *
 * <p>Four separate defects had one cause: the query lived as a mutable field
 * that each rebuild path had to remember to copy. The auction prompt set the
 * field but never rebuilt, the orders prompt set it on an instance it then threw
 * away, and the market and shop page arrows built the next GUI without it. The
 * first half of this class tests the extracted {@link GuiSearchView} directly;
 * the second half drives the real GUIs with a mocked server and asserts that the
 * GUI the player is shown next still carries the query.
 *
 * <p>The GUIs are driven rather than instantiated only because the rebuild is
 * the thing that was broken. Stacks are intercepted with {@code mockConstruction}
 * because {@code new ItemStack} needs a live registry and this suite has no
 * server, the same reason every other GUI-touching test here mocks it.
 */
class GuiSearchTest {

    private MockedStatic<Bukkit> bukkit;
    private MockedConstruction<ItemStack> stacks;
    private AurelEconomy plugin;
    private Player player;
    private ChatPromptManager prompts;
    private final List<Inventory> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        BlockStateMeta meta = mock(BlockStateMeta.class);
        when(meta.getBlockState()).thenReturn(mock(CreatureSpawner.class));
        stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.clone()).thenReturn(stack);
            when(stack.getItemMeta()).thenReturn(meta);
        });

        Server server = mock(Server.class);
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class)))
                .thenAnswer(call -> newInventory(call.getArgument(0)));
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt()))
                .thenAnswer(call -> newInventory(call.getArgument(0)));
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getName()).thenReturn("Jim");
        bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(offline);

        when(server.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class)))
                .thenAnswer(call -> newInventory(call.getArgument(0)));
        when(server.createInventory(any(InventoryHolder.class), anyInt()))
                .thenAnswer(call -> newInventory(call.getArgument(0)));

        AuctionManager auctions = mock(AuctionManager.class);
        when(auctions.getActiveAuctions()).thenReturn(List.of());

        OrderManager orders = mock(OrderManager.class);
        BuyOrder diamondOrder = order(Material.DIAMOND);
        BuyOrder coalOrder = order(Material.COAL);
        when(orders.getActiveOrders()).thenReturn(List.of(diamondOrder, coalOrder));

        MarketManager market = mock(MarketManager.class);
        when(market.isBlacklisted(any())).thenReturn(false);
        when(market.entryKey(any(MarketEntry.class))).thenReturn("test:entry");
        when(market.getBuyPrice(any(Material.class))).thenReturn(BigDecimal.ZERO);
        when(market.getCurrency(any(Material.class))).thenReturn("Aurels");
        when(market.getBuyPrice(anyString())).thenReturn(BigDecimal.ZERO);
        when(market.getCurrency(anyString())).thenReturn("Aurels");

        EconomyManager economy = mock(EconomyManager.class);
        when(economy.getFormattedWithSymbol(any(BigDecimal.class), anyString())).thenReturn("$0.00");

        prompts = mock(ChatPromptManager.class);

        plugin = mock(AurelEconomy.class);
        when(plugin.namespace()).thenReturn("aurelium");
        when(plugin.getName()).thenReturn("Aurelium");
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getAuctionManager()).thenReturn(auctions);
        when(plugin.getOrderManager()).thenReturn(orders);
        when(plugin.getMarketManager()).thenReturn(market);
        when(plugin.getEconomyManager()).thenReturn(economy);
        when(plugin.getChatPromptManager()).thenReturn(prompts);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        doAnswer(call -> {
            opened.add(call.getArgument(0));
            return null;
        }).when(player).openInventory(any(Inventory.class));
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        stacks.close();
    }

    // ------------------------------------------------------------------ state

    @Test
    @DisplayName("a blank query filters nothing and reports itself inactive")
    void blankQueryDoesNotFilter() {
        List<String> names = List.of("Diamond Sword", "Iron Ingot");

        GuiSearchView view = GuiSearchView.of("   ");

        assertFalse(view.active());
        assertEquals(names, view.filter(names, name -> name), "blank is not a filter");
        assertEquals(GuiSearchView.none(), view, "blank normalises to the empty view");
    }

    @Test
    @DisplayName("a query keeps only the names containing it, ignoring case")
    void queryFiltersCaseInsensitively() {
        List<String> names = List.of("Diamond Sword", "Iron Ingot", "DIAMOND Block");

        GuiSearchView view = GuiSearchView.of("diamond");

        assertEquals(List.of("Diamond Sword", "DIAMOND Block"), view.filter(names, name -> name));
        assertTrue(view.matches("Diamond Sword"));
        assertFalse(view.matches("Iron Ingot"));
        assertFalse(view.matches(null), "a missing name cannot match an active query");
    }

    @Test
    @DisplayName("turning the page keeps the query")
    void nextPageKeepsTheQuery() {
        GuiSearchView view = GuiSearchView.of("diamond");

        GuiSearchView next = view.next();

        assertEquals("diamond", next.query(), "page navigation must not drop the filter");
        assertEquals(1, next.page());
        assertSame(view, view.previous(), "the first page has no previous page to show");
    }

    @Test
    @DisplayName("a new query starts on the first page")
    void newQueryStartsAtTheFirstPage() {
        GuiSearchView view = GuiSearchView.of("diamond").withPage(4);

        GuiSearchView replaced = view.withQuery("coal");

        assertEquals("coal", replaced.query());
        assertEquals(0, replaced.page(), "keeping page 4 would show an empty page for a shorter result set");
    }

    @Test
    @DisplayName("page count is never zero and paging is bounded")
    void pageCountIsBounded() {
        GuiSearchView view = GuiSearchView.none();

        assertEquals(1, view.pageCount(0, 45), "an empty list still shows page 1 of 1");
        assertEquals(1, view.pageCount(45, 45));
        assertEquals(3, view.pageCount(91, 45));
        assertFalse(view.hasNext(45, 45), "one full page has no next page");
        assertTrue(view.hasNext(46, 45));
        assertTrue(view.withPage(1).hasNext(91, 45), "the middle page has a next page");
        assertFalse(view.withPage(2).hasNext(91, 45));
    }

    // --------------------------------------------------------------- wiring

    @Test
    @DisplayName("the auction prompt rebuilds the inventory, so the query takes effect")
    void auctionPromptRefreshesTheInventory() {
        AuctionGUI gui = new AuctionGUI(plugin, player, false);
        Inventory inventory = gui.getInventory();

        click(gui, 46);
        answerPrompt("diamond");

        verify(inventory, times(1)).clear();
        verify(player, atLeastOnce()).openInventory(inventory);
    }

    @Test
    @DisplayName("the orders prompt opens a GUI that carries the query")
    void ordersPromptCarriesTheQueryIntoTheRebuiltGui() {
        OrdersGUI gui = new OrdersGUI(plugin, player, GuiSearchView.none());

        click(gui, 46);
        answerPrompt("diamond");

        OrdersGUI openedGui = (OrdersGUI) lastOpenedHolder();
        assertEquals("diamond", openedGui.view().query());
        assertEquals(0, openedGui.view().page());
    }

    @Test
    @DisplayName("the market page arrow opens the next page with the query intact")
    void marketPageTurnKeepsTheQuery() {
        String query = broadQuery();
        MarketGUI gui = new MarketGUI(plugin, player, Category.ALL_ITEMS, GuiSearchView.of(query));

        click(gui, 50);

        MarketGUI openedGui = (MarketGUI) lastOpenedHolder();
        assertEquals(query, openedGui.view().query(), "the page arrow dropped the search query");
        assertEquals(1, openedGui.view().page());
    }

    @Test
    @DisplayName("the shop search prompt rebuilds with the query as constructor state")
    void shopPromptRebuildsWithTheQuery() {
        // The categories screen: slot 4 opens the prompt without playing a sound,
        // which is why this path is used rather than the page arrow. The arrow
        // shares the same view, and the page-number constructors are gone, so a
        // rebuild cannot be expressed without the query any more.
        ShopGUI gui = new ShopGUI(plugin, player);

        click(gui, 4);
        answerPrompt("spawner");

        ShopGUI openedGui = (ShopGUI) lastOpenedHolder();
        assertEquals("spawner", openedGui.view().query(), "the rebuilt screen lost the search query");
        assertEquals(0, openedGui.view().page());
    }

    // --------------------------------------------------------------- helpers

    /**
     * A query that matches enough catalogue entries for a second page to exist.
     * Spawners are used because they are the entries with a stored custom name,
     * and the screens render those without asking the server for a material
     * translation key, which needs a live registry this suite does not have.
     */
    private static String broadQuery() {
        String query = "spawner";
        long matches = MarketItems.getItems(Category.ALL_ITEMS).stream()
                .filter(entry -> entry.searchName.contains(query))
                .count();
        assertTrue(matches > 45, "expected more than one page of spawners, found " + matches);
        return query;
    }

    private static BuyOrder order(Material material) {
        BuyOrder order = mock(BuyOrder.class);
        when(order.getMaterial()).thenReturn(material);
        when(order.getBuyerUuid()).thenReturn(UUID.randomUUID());
        when(order.getPricePerPiece()).thenReturn(BigDecimal.ONE);
        when(order.getCurrency()).thenReturn("Aurels");
        when(order.getAmountRequested()).thenReturn(64);
        when(order.getAmountFilled()).thenReturn(0);
        return order;
    }

    private Inventory newInventory(InventoryHolder holder) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(holder);
        return inventory;
    }

    private GUIHolder lastOpenedHolder() {
        assertFalse(opened.isEmpty(), "nothing was opened");
        InventoryHolder holder = opened.get(opened.size() - 1).getHolder();
        assertTrue(holder instanceof GUIHolder, "the opened inventory is not a GUI");
        return (GUIHolder) holder;
    }

    private void click(GUIHolder gui, int slot) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getSlot()).thenReturn(slot);
        when(event.getRawSlot()).thenReturn(slot);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(mock(ItemStack.class));
        when(event.isShiftClick()).thenReturn(false);
        gui.handleClick(event);
    }

    @SuppressWarnings("unchecked")
    private void answerPrompt(String input) {
        ArgumentCaptor<Consumer<String>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(prompts, atLeastOnce()).prompt(eq(player), callback.capture());
        callback.getValue().accept(input);
    }
}

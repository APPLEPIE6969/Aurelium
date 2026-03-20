package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.orders.BuyOrder;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

import org.bukkit.event.inventory.InventoryClickEvent;

public class OrdersGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final Inventory inventory;
    private int page;
    private String searchQuery = null;
    private final List<BuyOrder> allOrders;

    public OrdersGUI(AurelEconomy plugin, Player player, int page) {
        this.plugin = plugin;
        this.player = player;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("Global Buy Orders", NamedTextColor.DARK_GRAY));
        this.allOrders = new java.util.ArrayList<>(plugin.getOrderManager().getActiveOrders());
        plugin.addViewer(player);
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query;
        this.page = 0;
        refresh();
    }

    public void open() {
        setupItems();
        player.openInventory(inventory);
    }

    public void refresh() {
        allOrders.clear();
        allOrders.addAll(plugin.getOrderManager().getActiveOrders());
        setupItems();
    }

    private void setupItems() {
        inventory.clear();

        List<BuyOrder> filtered = allOrders;
        if (searchQuery != null && !searchQuery.isBlank()) {
            final String q = searchQuery.toLowerCase();
            filtered = allOrders.stream()
                    .filter(o -> o.getMaterial().name().toLowerCase().contains(q))
                    .toList();
        }

        int startIdx = page * 45;
        for (int i = 0; i < 45; i++) {
            if (startIdx + i >= filtered.size())
                break;

            BuyOrder order = filtered.get(startIdx + i);
            String displayMat = order.getMaterial().name().replace("_", " ").toLowerCase();
            String playerName = Bukkit.getOfflinePlayer(order.getBuyerUuid()).getName();
            if (playerName == null)
                playerName = "Unknown";

            inventory.setItem(i, new ItemBuilder(order.getMaterial())
                    .name(Component.text("Buying: " + displayMat, NamedTextColor.GOLD, TextDecoration.BOLD))
                    .lore(
                            Component.empty(),
                            Component.text("Buyer: ", NamedTextColor.GRAY)
                                    .append(Component.text(playerName, NamedTextColor.WHITE)),
                            Component.text("Price Per Piece: ", NamedTextColor.GRAY)
                                    .append(Component.text(
                                            plugin.getEconomyManager().getFormattedWithSymbol(order.getPricePerPiece(),
                                                    order.getCurrency()),
                                            NamedTextColor.GREEN)),
                            Component.text("Requested: ", NamedTextColor.GRAY)
                                    .append(Component.text(order.getAmountRequested(), NamedTextColor.YELLOW)),
                            Component.text("Filled: ", NamedTextColor.GRAY)
                                    .append(Component.text(order.getAmountFilled(), NamedTextColor.AQUA)),
                            Component.text("Remaining: ", NamedTextColor.GRAY)
                                    .append(Component.text(order.getAmountRemaining(), NamedTextColor.RED)),
                            Component.empty(),
                            Component.text("Right-Click to Fulfill Order", NamedTextColor.LIGHT_PURPLE,
                                    TextDecoration.ITALIC),
                            Component.text("ID: " + order.getId(), NamedTextColor.DARK_GRAY))
                    .build());
        }

        // Bottom Row navigation
        inventory.setItem(45,
                new ItemBuilder(Material.PAPER)
                        .name(Component.text("Create Buy Order", NamedTextColor.GREEN, TextDecoration.BOLD))
                        .lore(Component.text("Click to request items", NamedTextColor.GRAY)).build());
        inventory.setItem(46,
                new ItemBuilder(Material.COMPASS).name(Component.text("Search Orders", NamedTextColor.GOLD))
                        .lore(Component.text("Find a specific item requested", NamedTextColor.GRAY)).build());

        if (page > 0) {
            inventory.setItem(48, new ItemBuilder(Material.ARROW)
                    .name(Component.text("Previous Page", NamedTextColor.YELLOW)).build());
        }

        inventory.setItem(49,
                new ItemBuilder(Material.BARRIER).name(Component.text("Close", NamedTextColor.RED)).build());

        if (startIdx + 45 < filtered.size()) {
            inventory.setItem(50,
                    new ItemBuilder(Material.ARROW).name(Component.text("Next Page", NamedTextColor.YELLOW)).build());
        }

        inventory.setItem(53,
                new ItemBuilder(Material.BOOK)
                        .name(Component.text("My Orders", NamedTextColor.AQUA, TextDecoration.BOLD))
                        .lore(Component.text("Manage and cancel your active orders", NamedTextColor.GRAY)).build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null)
            return;

        int slot = event.getRawSlot();
        if (slot == 45) { // Create Buy Order
            new OrderCategoryGUI(plugin, player).open();
        } else if (slot == 46) { // Search Orders
            player.closeInventory();
            player.sendMessage(
                    Component.text("Enter an item name to search for (e.g. 'diamond'):", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("Type 'cancel' or 'clear' to reset.", NamedTextColor.GRAY));

            plugin.getChatPromptManager().prompt(player, query -> {
                if (query.equalsIgnoreCase("cancel")) {
                    player.sendMessage(Component.text("Search cancelled.", NamedTextColor.RED));
                    new OrdersGUI(plugin, player, 0).open();
                    return;
                }
                if (query.equalsIgnoreCase("clear")) {
                    setSearchQuery(null);
                    new OrdersGUI(plugin, player, 0).open();
                    return;
                }
                setSearchQuery(query);
                new OrdersGUI(plugin, player, 0).open(); // Open a fresh GUI so the new search applies
            });
        } else if (slot == 48 && page > 0) {
            page--;
            refresh();
        } else if (slot == 49) {
            player.closeInventory();
        } else if (slot == 50 && (page * 45) + 45 < allOrders.size()) {
            page++;
            refresh();
        } else if (slot == 53) { // My Orders
            new MyOrdersGUI(plugin, player, 0).open();
        } else if (slot < 45) {
            // Fulfillment logic handled here
            List<BuyOrder> filtered = allOrders;
            if (searchQuery != null && !searchQuery.isBlank()) {
                final String q = searchQuery.toLowerCase();
                filtered = allOrders.stream()
                        .filter(o -> o.getMaterial().name().toLowerCase().contains(q))
                        .toList();
            }

            int orderIndex = (page * 45) + slot;
            if (orderIndex < filtered.size()) {
                BuyOrder order = filtered.get(orderIndex);

                // Check if player is the buyer
                if (order.getBuyerUuid().equals(player.getUniqueId())) {
                    player.sendMessage(Component.text("You cannot fulfill your own buy order.", NamedTextColor.RED));
                    return;
                }

                // Trigger fulfillment prompt
                player.closeInventory();

                String itemName = order.getMaterial().name().replace("_", " ").toLowerCase();
                player.sendMessage(Component.text("How many " + itemName + " do you want to sell to this order?",
                        NamedTextColor.YELLOW));
                player.sendMessage(
                        Component.text("Max needed: " + order.getAmountRemaining() + ". Type a number or 'cancel'.",
                                NamedTextColor.GRAY));

                plugin.getChatPromptManager().prompt(player, amountStr -> {
                    if (amountStr.equalsIgnoreCase("cancel")) {
                        player.sendMessage(Component.text("Fulfillment cancelled.", NamedTextColor.RED));
                        return;
                    }
                    try {
                        int amount = Integer.parseInt(amountStr);
                        if (amount <= 0)
                            throw new NumberFormatException();

                        // Execute fulfillment
                        plugin.getOrderManager().fillOrder(player, order.getId(), amount);

                    } catch (NumberFormatException ex) {
                        player.sendMessage(
                                Component.text("Invalid quantity. Fulfillment cancelled.", NamedTextColor.RED));
                    }
                });
            }
        }
    }
}

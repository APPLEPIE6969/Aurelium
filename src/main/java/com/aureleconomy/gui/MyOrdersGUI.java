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

public class MyOrdersGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final Inventory inventory;
    private int page;
    private final List<BuyOrder> myOrders;

    public MyOrdersGUI(AurelEconomy plugin, Player player, int page) {
        this.plugin = plugin;
        this.player = player;
        this.page = page;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("My Active Orders", NamedTextColor.DARK_GRAY));
        this.myOrders = plugin.getOrderManager().getOrdersByPlayer(player.getUniqueId());
        plugin.addViewer(player);
    }

    public void open() {
        setupItems();
        player.openInventory(inventory);
    }

    public void refresh() {
        setupItems();
    }

    private void setupItems() {
        inventory.clear();

        int startIdx = page * 45;
        for (int i = 0; i < 45; i++) {
            if (startIdx + i >= myOrders.size())
                break;

            BuyOrder order = myOrders.get(startIdx + i);
            String displayMat = order.getMaterial().name().replace("_", " ").toLowerCase();

            inventory.setItem(i, new ItemBuilder(order.getMaterial())
                    .name(Component.text("Buying: " + displayMat, NamedTextColor.GOLD, TextDecoration.BOLD))
                    .lore(
                            Component.empty(),
                            Component.text("Price Per Piece: ", NamedTextColor.GRAY)
                                    .append(Component.text(
                                            plugin.getEconomyManager().format(order.getPricePerPiece(),
                                                    order.getCurrency()),
                                            NamedTextColor.GREEN)),
                            Component.text("Requested: ", NamedTextColor.GRAY)
                                    .append(Component.text(order.getAmountRequested(), NamedTextColor.YELLOW)),
                            Component.text("Filled: ", NamedTextColor.GRAY)
                                    .append(Component.text(order.getAmountFilled(), NamedTextColor.AQUA)),
                            Component.text("Remaining: ", NamedTextColor.GRAY)
                                    .append(Component.text(order.getAmountRemaining(), NamedTextColor.RED)),
                            Component.empty(),
                            Component.text("Right-Click to Cancel Order", NamedTextColor.RED, TextDecoration.ITALIC),
                            Component.text("Refunds remaining items to your balance.", NamedTextColor.GRAY),
                            Component.text("ID: " + order.getId(), NamedTextColor.DARK_GRAY))
                    .build());
        }

        // Bottom Row navigation
        inventory.setItem(45, new ItemBuilder(Material.BARRIER)
                .name(Component.text("Back to Global Orders", NamedTextColor.RED)).build());

        if (page > 0) {
            inventory.setItem(48, new ItemBuilder(Material.ARROW)
                    .name(Component.text("Previous Page", NamedTextColor.YELLOW)).build());
        }

        if (startIdx + 45 < myOrders.size()) {
            inventory.setItem(50,
                    new ItemBuilder(Material.ARROW).name(Component.text("Next Page", NamedTextColor.YELLOW)).build());
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null)
            return;

        int slot = event.getRawSlot();
        if (slot == 45) { // Back to global
            new OrdersGUI(plugin, player, 0).open();
        } else if (slot == 48 && page > 0) {
            page--;
            refresh();
        } else if (slot == 50 && (page * 45) + 45 < myOrders.size()) {
            page++;
            refresh();
        } else if (slot < 45 && event.isRightClick()) {
            int orderIndex = (page * 45) + slot;
            if (orderIndex < myOrders.size()) {
                BuyOrder order = myOrders.get(orderIndex);
                plugin.getOrderManager().cancelOrder(player, order.getId());
                myOrders.remove(orderIndex); // Optimistically remove from local list
                refresh();
            }
        }
    }
}

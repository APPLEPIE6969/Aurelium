package com.aureleconomy.listeners;

import com.aureleconomy.gui.GUIHolder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public class GUIListener implements Listener {

    private final com.aureleconomy.AurelEconomy plugin;

    public GUIListener(com.aureleconomy.AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        org.bukkit.inventory.InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof com.aureleconomy.gui.StocksGUI
                || holder instanceof com.aureleconomy.gui.MarketGUI
                || holder instanceof com.aureleconomy.gui.AuctionGUI
                || holder instanceof com.aureleconomy.gui.OrdersGUI
                || holder instanceof com.aureleconomy.gui.OrderCategoryGUI
                || holder instanceof com.aureleconomy.gui.OrderMaterialGUI
                || holder instanceof com.aureleconomy.gui.MyOrdersGUI) {
            plugin.addViewer((org.bukkit.entity.Player) event.getPlayer());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof GUIHolder gui) {
            gui.handleClick(event);
        }
    }

    @EventHandler
    public void onDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GUIHolder gui) {
            gui.handleDrag(event);
        }
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof GUIHolder gui) {
            gui.handleClose(event);
            plugin.removeViewer((org.bukkit.entity.Player) event.getPlayer());
        }
    }
}

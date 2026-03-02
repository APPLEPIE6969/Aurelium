package com.aureleconomy.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

public abstract class GUIHolder implements InventoryHolder {

    protected Inventory inventory;

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public abstract void handleClick(org.bukkit.event.inventory.InventoryClickEvent event);

    public void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        // Default implementation: do nothing
    }

    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        // Default implementation: do nothing
    }

    public void open(org.bukkit.entity.Player player) {
        player.openInventory(getInventory());
    }

    public void open() {
        // Only if player is already known? No, we need a player.
        // Some GUIs store player, some don't.
    }
}

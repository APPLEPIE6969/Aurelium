package com.aureleconomy.utils;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class InventoryUtils {

    /**
     * Calculates the total available space for a specific ItemStack in an
     * inventory.
     * Considers both empty slots (up to max stack size) and partial stacks of the
     * same item.
     */
    public static int getAvailableSpace(Inventory inventory, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }

        int space = 0;
        int maxStackSize = item.getMaxStackSize();

        for (ItemStack slotItem : inventory.getStorageContents()) {
            if (slotItem == null || slotItem.getType().isAir()) {
                space += maxStackSize;
            } else if (slotItem.isSimilar(item)) {
                space += Math.max(0, maxStackSize - slotItem.getAmount());
            }
        }

        return space;
    }

    /**
     * Checks if the inventory has enough space for a given quantity of an item.
     */
    public static boolean hasSpace(Inventory inventory, ItemStack item, int amountRequired) {
        return getAvailableSpace(inventory, item) >= amountRequired;
    }
}

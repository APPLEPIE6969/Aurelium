package com.aureleconomy.utils;

import org.bukkit.entity.Player;
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

    /**
     * Removes {@code expected} from the player's main hand, but only if the hand
     * still holds that exact stack.
     *
     * <p>Listing an item to the auction house persists asynchronously, so the
     * hand is only cleared once the insert is confirmed. In that window the
     * player can swap or drop the stack; clearing unconditionally would then
     * destroy an unrelated item, so the match is verified first.
     *
     * @return true if the stack was cleared
     */
    public static boolean clearMainHandIfSimilar(Player player, ItemStack expected) {
        if (player == null || expected == null) {
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir() || !held.isSimilar(expected)) {
            return false;
        }
        player.getInventory().setItemInMainHand(null);
        return true;
    }

    /**
     * Returns an item to the player, falling back to dropping it at their feet
     * when the inventory is full so a failed trade can never destroy it.
     *
     * @return true if the item went into the inventory
     */
    public static boolean giveOrDrop(Player player, ItemStack item) {
        if (player == null || item == null || item.getType().isAir()) {
            return false;
        }
        if (!hasSpace(player.getInventory(), item, item.getAmount())) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
            return false;
        }
        player.getInventory().addItem(item);
        return true;
    }
}

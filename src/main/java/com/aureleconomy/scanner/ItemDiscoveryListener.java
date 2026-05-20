package com.aureleconomy.scanner;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Runtime detection of custom items via player interactions.
 * All events use MONITOR priority and ignore cancelled events
 * to avoid interfering with gameplay.
 */
public class ItemDiscoveryListener implements Listener {

    private final UnifiedItemScanner scanner;

    public ItemDiscoveryListener(UnifiedItemScanner scanner) {
        this.scanner = scanner;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType().isAir()) return;
        scanner.scanSingleItem(item, DiscoveryMethod.INTERACTION_DETECT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current != null && !current.getType().isAir()) {
            scanner.scanSingleItem(current, DiscoveryMethod.INTERACTION_DETECT);
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            scanner.scanSingleItem(cursor, DiscoveryMethod.INTERACTION_DETECT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        ItemStack item = event.getItem().getItemStack();
        if (item != null && !item.getType().isAir()) {
            scanner.scanSingleItem(item, DiscoveryMethod.INTERACTION_DETECT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        ItemStack result = event.getRecipe().getResult();
        if (result != null && !result.getType().isAir()) {
            scanner.scanSingleItem(result, DiscoveryMethod.INTERACTION_DETECT);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                scanner.scanSingleItem(item, DiscoveryMethod.INTERACTION_DETECT);
            }
        }
    }
}

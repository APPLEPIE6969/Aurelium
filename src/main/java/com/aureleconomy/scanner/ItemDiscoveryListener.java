package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime detection of custom items via player interactions.
 * All events use MONITOR priority and ignore cancelled events
 * to avoid interfering with gameplay.
 *
 * Respects the interaction-detect config flag and rate-limits
 * scanning per-player to avoid main-thread lag from reflection.
 */
public class ItemDiscoveryListener implements Listener {

    private final UnifiedItemScanner scanner;
    private final AurelEconomy plugin;

    // Rate-limit: max 1 scan per player per 2 seconds
    private final ConcurrentHashMap<java.util.UUID, AtomicLong> lastScan = new ConcurrentHashMap<>();
    private static final long SCAN_COOLDOWN_MS = 2000;

    public ItemDiscoveryListener(AurelEconomy plugin, UnifiedItemScanner scanner) {
        this.plugin = plugin;
        this.scanner = scanner;
    }

    private boolean isInteractionDetectEnabled() {
        return plugin.getConfig().getBoolean("custom-items.discovery-methods.interaction-detect", true);
    }

    private boolean shouldScan(org.bukkit.entity.Player player) {
        if (!isInteractionDetectEnabled()) return false;
        AtomicLong last = lastScan.computeIfAbsent(player.getUniqueId(), k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long prev = last.get();
        if (now - prev < SCAN_COOLDOWN_MS) return false;
        return last.compareAndSet(prev, now);
    }

    private void tryScan(org.bukkit.entity.Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        if (!shouldScan(player)) return;
        scanner.scanSingleItem(item, DiscoveryMethod.INTERACTION_DETECT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        tryScan(event.getPlayer(), event.getItem());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) return;
        ItemStack current = event.getCurrentItem();
        if (current != null && !current.getType().isAir()) {
            tryScan(player, current);
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            tryScan(player, cursor);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        tryScan(player, item);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) return;
        ItemStack result = event.getRecipe().getResult();
        tryScan(player, result);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof org.bukkit.entity.Player player)) return;
        if (!isInteractionDetectEnabled()) return;
        for (ItemStack item : event.getInventory().getContents()) {
            if (item != null && !item.getType().isAir()) {
                scanner.scanSingleItem(item, DiscoveryMethod.INTERACTION_DETECT);
            }
        }
    }
}

package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SellGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;

    public SellGUI(AurelEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = plugin.getServer().createInventory(this, 54, Component.text("Sell Items - Drag & Drop"));
        setupInterface();
    }

    private void setupInterface() {
        // Bottom row (45-53) is for controls.
        // 45-53 excluding 49 (Confirm) and 45 (Cancel/Exit) can be filler or just
        // empty?
        // Let's use gray glass for the bottom row filler.
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();

        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        // Cancel Button (45)
        inventory.setItem(45, new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name(Component.text("Cancel & Return Items", NamedTextColor.RED))
                .build());

        // Confirm Button (49) -> We will update this dynamically?
        // Actually, let's make it a static 'Click to Sell' button, and we calc total on
        // click.
        // Updating lore every slot change is expensive/complex with GUIHolder logic
        // unless we listen to all events.
        // Simple approach: Click confirm -> Calc total -> Ask "Sold for X?" or just
        // sell.
        // User asked: "ask are you sure... click yes or no".
        // So:
        // 1. User drags items.
        // 2. User clicks "Review/Sell" (Slot 49).
        // 3. GUI changes to "Confirm? Total: $XXX" (Yes/No).
        // OR: Slot 49 just says "Click to Sell All".
        // Let's do: Slot 49 is "Sell All". Click it -> Checks items, Calc Total.
        // If > 0, sell immediately? Or pop up confirmation?
        // User said: "it will ask are you sure you want to sell this and you click yes
        // or no".
        // So we need a confirmation state.

        inventory.setItem(49, new ItemBuilder(Material.EMERALD_BLOCK)
                .name(Component.text("Sell All", NamedTextColor.GREEN))
                .lore(Component.text("Click to calculate value", NamedTextColor.GRAY))
                .build());

        // Sell Matching (Hopper) - Slot 53
        inventory.setItem(53, new ItemBuilder(Material.HOPPER)
                .name(Component.text("Sell Matching", NamedTextColor.AQUA))
                .lore(Component.text("Drag an item here to", NamedTextColor.GRAY),
                        Component.text("sell all matching items", NamedTextColor.GRAY))
                .build());
    }

    private boolean isConfirming = false;

    @Override
    public void handleClick(InventoryClickEvent event) {
        int slot = event.getSlot();

        // Allow top interaction (0-44)
        if (slot < 45 && slot >= 0) {
            if (isConfirming) {
                event.setCancelled(true);
                return;
            }
            return; // Allow
        }

        event.setCancelled(true); // Cancel bottom row clicks

        if (slot == 45) {
            player.closeInventory();
            return;
        }

        if (slot == 49) {
            if (!isConfirming) {
                calculateAndPrompt();
            } else {
                performSell();
            }
        }

        // Sell Matching Logic (Slot 53)
        if (slot == 53 && !isConfirming) {
            // Check cursor item
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                pullMatchingItems(cursor);
            } else {
                // Should we allow clicking if they have nothing on cursor?
                // Instructions said "drag your item on that".
                // So we expect a cursor item.
                player.sendMessage(
                        Component.text("Drag an item here to sell all matching types!", NamedTextColor.YELLOW));
            }
        }
    }

    private void pullMatchingItems(ItemStack template) {
        Material type = template.getType();
        // Iterate player inventory
        Inventory pInv = player.getInventory();
        int addedCount = 0;

        for (int i = 0; i < pInv.getSize(); i++) {
            ItemStack item = pInv.getItem(i);
            if (item != null && item.getType() == type) {
                // Try to add to GUI
                // We should NOT remove the one being dragged (the cursor).
                // But the cursor is technically 'held' by player?
                // Actually, if they click Slot 53, the event carries the cursor.
                // We shouldn't take the cursor item itself, just the others?
                // "sell all of the same item".
                // Let's take all from inventory.
                // The cursor item remains on cursor unless we set it to air.
                // If we want to include the cursor item, we just add it to the GUI and set
                // cursor air.

                // Let's move inventory items first.
                // Find first empty slot in GUI (0-44)

                // We need to loop and fit as many as possible.
                HashMap<Integer, ItemStack> leftover = inventory.addItem(item);
                if (leftover.isEmpty()) {
                    pInv.setItem(i, null); // Successfully moved
                    addedCount += item.getAmount();
                } else {
                    // Full
                    pInv.setItem(i, leftover.get(0)); // Put back remainder
                    addedCount += (item.getAmount() - leftover.get(0).getAmount());
                    break; // GUI Full
                }
            }
        }

        // Include cursor item?
        HashMap<Integer, ItemStack> cursorLeftover = inventory.addItem(template);
        if (cursorLeftover.isEmpty()) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
            addedCount += template.getAmount();
        } else {
            player.setItemOnCursor(cursorLeftover.get(0));
            addedCount += (template.getAmount() - cursorLeftover.get(0).getAmount());
        }

        if (addedCount > 0) {
            player.sendMessage(Component.text("Moved " + addedCount + " items to sell area.", NamedTextColor.GREEN));
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1, 1);
            calculateAndPrompt();
        } else {
            player.sendMessage(Component.text("No more items or Sell GUI full!", NamedTextColor.RED));
        }
    }

    private String getItemKey(ItemStack item) {
        if (item.getType() == Material.SPAWNER) {
            try {
                org.bukkit.inventory.meta.BlockStateMeta meta = (org.bukkit.inventory.meta.BlockStateMeta) item
                        .getItemMeta();
                org.bukkit.block.CreatureSpawner spawner = (org.bukkit.block.CreatureSpawner) meta.getBlockState();
                String typeName = spawner.getSpawnedType().name();
                String[] words = typeName.split("_");
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < words.length; j++) {
                    sb.append(words[j].substring(0, 1).toUpperCase())
                            .append(words[j].substring(1).toLowerCase());
                    if (j < words.length - 1)
                        sb.append(" ");
                }
                return sb.toString() + " Spawner";
            } catch (Exception ignored) {
            }
        }
        return item.getType().name();
    }

    public void handleDrag(InventoryDragEvent event) {
        for (int slot : event.getRawSlots()) {
            if (slot >= 45 && slot < 54) {
                event.setCancelled(true);
                return;
            }
        }
        if (isConfirming) {
            event.setCancelled(true);
        }
    }

    private void calculateAndPrompt() {
        Map<String, Double> totals = new HashMap<>();
        boolean hasSellable = false;

        for (int i = 0; i < 45; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                String key = getItemKey(item);
                double price = plugin.getMarketManager().getSellPrice(key);
                String currency = plugin.getMarketManager().getCurrency(key);

                if (price > 0) {
                    totals.put(currency, totals.getOrDefault(currency, 0.0) + (price * item.getAmount()));
                    hasSellable = true;
                }
            }
        }

        if (!hasSellable) {
            player.sendMessage(Component.text("No sellable items found.", NamedTextColor.RED));
            return;
        }

        this.isConfirming = true;

        inventory.setItem(45, new ItemBuilder(Material.RED_CONCRETE)
                .name(Component.text("Cancel", NamedTextColor.RED))
                .build());

        ItemBuilder confirmButton = new ItemBuilder(Material.LIME_CONCRETE)
                .name(Component.text("Confirm Sell", NamedTextColor.GREEN));

        for (Map.Entry<String, Double> entry : totals.entrySet()) {
            confirmButton.lore(
                    Component.text("Value: " + plugin.getEconomyManager().format(entry.getValue(), entry.getKey()),
                            NamedTextColor.YELLOW));
        }
        confirmButton.lore(Component.text("Click to confirm", NamedTextColor.GRAY));

        inventory.setItem(49, confirmButton.build());

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
    }

    private void performSell() {
        Map<String, Double> finalTotals = new HashMap<>();
        List<ItemStack> unsold = new ArrayList<>();

        for (int i = 0; i < 45; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                String key = getItemKey(item);
                double price = plugin.getMarketManager().getSellPrice(key);
                String currency = plugin.getMarketManager().getCurrency(key);
                if (price > 0) {
                    finalTotals.put(currency, finalTotals.getOrDefault(currency, 0.0) + (price * item.getAmount()));
                    inventory.setItem(i, null); // Remove sold item
                    plugin.getMarketManager().onTransaction(key, false, item.getAmount()); // Trigger Market Supply Drop
                } else {
                    unsold.add(item); // Keep unsold
                }
            }
        }

        if (!finalTotals.isEmpty()) {
            for (Map.Entry<String, Double> entry : finalTotals.entrySet()) {
                plugin.getEconomyManager().deposit(player, entry.getValue(), entry.getKey());
                player.sendMessage(Component.text(
                        "Sold items for " + plugin.getEconomyManager().format(entry.getValue(), entry.getKey()),
                        NamedTextColor.GREEN));
            }
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        }

        // Return unsold items/close
        // If we close, the InventoryCloseEvent should handle returning items left in
        // top slots.
        // But we just removed sold ones.
        // So just close.
        player.closeInventory();
    }

    public void handleClose(InventoryCloseEvent event) {
        // Return matching items from top slots (0-44)
        Inventory inv = event.getInventory();
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                player.getInventory().addItem(item).forEach((k, v) -> {
                    player.getWorld().dropItem(player.getLocation(), v);
                });
            }
        }
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }
}

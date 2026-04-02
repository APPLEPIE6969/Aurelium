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

import java.math.BigDecimal;
import java.util.HashMap;
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
        ItemStack filler = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name(Component.empty()).build();

        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, filler);
        }

        inventory.setItem(45, new ItemBuilder(Material.RED_STAINED_GLASS_PANE)
                .name(Component.text("Cancel & Return Items", NamedTextColor.RED))
                .build());

        inventory.setItem(49, new ItemBuilder(Material.EMERALD_BLOCK)
                .name(Component.text("Sell All", NamedTextColor.GREEN))
                .lore(Component.text("Click to calculate value", NamedTextColor.GRAY))
                .build());

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

        if (slot < 45 && slot >= 0) {
            if (isConfirming) {
                event.setCancelled(true);
                return;
            }
            return; 
        }

        event.setCancelled(true); 

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

        if (slot == 53 && !isConfirming) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && cursor.getType() != Material.AIR) {
                pullMatchingItems(cursor);
            } else {
                player.sendMessage(
                        Component.text("Drag an item here to sell all matching types!", NamedTextColor.YELLOW));
            }
        }
    }

    private void pullMatchingItems(ItemStack template) {
        Material type = template.getType();
        Inventory pInv = player.getInventory();
        int addedCount = 0;

        for (int i = 0; i < pInv.getSize(); i++) {
            ItemStack item = pInv.getItem(i);
            if (item != null && item.getType() == type) {
                HashMap<Integer, ItemStack> leftover = inventory.addItem(item);
                if (leftover.isEmpty()) {
                    pInv.setItem(i, null); 
                    addedCount += item.getAmount();
                } else {
                    pInv.setItem(i, leftover.get(0)); 
                    addedCount += (item.getAmount() - leftover.get(0).getAmount());
                    break; 
                }
            }
        }

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

    private Map<String, BigDecimal> cachedTotals = new HashMap<>();

    private void calculateAndPrompt() {
        cachedTotals.clear();
        boolean hasSellable = false;

        for (int i = 0; i < 45; i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                String key = getItemKey(item);
                BigDecimal price = plugin.getMarketManager().getSellPrice(key);
                String currency = plugin.getMarketManager().getCurrency(key);

                if (price.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal amount = BigDecimal.valueOf(item.getAmount());
                    cachedTotals.put(currency, cachedTotals.getOrDefault(currency, BigDecimal.ZERO).add(price.multiply(amount)));
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

        for (Map.Entry<String, BigDecimal> entry : cachedTotals.entrySet()) {
            confirmButton.lore(
                    Component.text("Value: " + plugin.getEconomyManager().getFormattedWithSymbol(entry.getValue(), entry.getKey()),
                            NamedTextColor.YELLOW));
        }
        confirmButton.lore(Component.text("Click to confirm", NamedTextColor.GRAY));

        inventory.setItem(49, confirmButton.build());

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
    }

    private void performSell() {
        if (!cachedTotals.isEmpty()) {
            Map<String, Integer> itemsToNotifyMarket = new HashMap<>();
            
            for (int i = 0; i < 45; i++) {
                ItemStack item = inventory.getItem(i);
                if (item != null && item.getType() != Material.AIR) {
                    String key = getItemKey(item);
                    BigDecimal price = plugin.getMarketManager().getSellPrice(key);
                    if (price.compareTo(BigDecimal.ZERO) > 0) {
                        itemsToNotifyMarket.put(key, itemsToNotifyMarket.getOrDefault(key, 0) + item.getAmount());
                        inventory.setItem(i, null); 
                    }
                }
            }

            for (Map.Entry<String, BigDecimal> entry : cachedTotals.entrySet()) {
                plugin.getEconomyManager().deposit(player, entry.getValue(), entry.getKey());
                player.sendMessage(Component.text()
                        .append(Component.text("Sold items for ", NamedTextColor.GREEN))
                        .append(Component.text(plugin.getEconomyManager().getFormattedWithSymbol(entry.getValue(), entry.getKey()), NamedTextColor.GOLD))
                        .build());
            }

            itemsToNotifyMarket.forEach((key, quantity) -> {
                plugin.getMarketManager().onTransaction(key, false, quantity);
            });

            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
            cachedTotals.clear();
        }

        player.closeInventory();
    }

    public void handleClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        for (int i = 0; i < 45; i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                player.getInventory().addItem(item).forEach((k, v) -> {
                    plugin.getAuctionManager().sendToCollectionBin(player.getUniqueId(), v);
                    player.sendMessage(Component.text("Inventory full! " + v.getAmount() + "x " + v.getType().name() + " sent to /ah collect.", NamedTextColor.YELLOW));
                });
            }
        }
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }
}

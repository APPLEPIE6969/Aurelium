package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItemFactory;
import com.aureleconomy.market.SellTransaction;
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

    /**
     * Moves everything the player holds that is the same item as {@code template}.
     *
     * <p>Matching uses the price key rather than the material, so dragging one
     * iron golem spawner no longer pulls every other spawner into the window where
     * it could not be sold (M2).
     */
    private void pullMatchingItems(ItemStack template) {
        String templateKey = MarketItemFactory.priceKey(plugin, template);
        if (templateKey == null) {
            player.sendMessage(Component.text(
                    "The market has no price for this item as it is, so it cannot be sold here.",
                    NamedTextColor.RED));
            return;
        }

        Inventory pInv = player.getInventory();
        int addedCount = 0;

        for (int i = 0; i < pInv.getSize(); i++) {
            ItemStack item = pInv.getItem(i);
            if (item != null && templateKey.equals(MarketItemFactory.priceKey(plugin, item))) {
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

    /**
     * Prices what is in the window now and shows the confirm button.
     *
     * <p>The totals on the button are a preview. The payout is taken from a fresh
     * quote on confirm, because prices move while the screen is open (M3).
     */
    private void calculateAndPrompt() {
        SellTransaction.Quote quote = new SellTransaction(plugin).quote(window());

        if (quote.isEmpty()) {
            player.sendMessage(Component.text("No sellable items found.", NamedTextColor.RED));
            if (quote.refused() > 0) {
                player.sendMessage(Component.text(quote.refused()
                        + " item(s) cannot be sold: the market has no price for them as they are.",
                        NamedTextColor.YELLOW));
            }
            return;
        }

        this.isConfirming = true;

        inventory.setItem(45, new ItemBuilder(Material.RED_CONCRETE)
                .name(Component.text("Cancel", NamedTextColor.RED))
                .build());

        ItemBuilder confirmButton = new ItemBuilder(Material.LIME_CONCRETE)
                .name(Component.text("Confirm Sell", NamedTextColor.GREEN));

        for (Map.Entry<String, BigDecimal> entry : quote.totals().entrySet()) {
            confirmButton.lore(
                    Component.text("Value: " + plugin.getEconomyManager().getFormattedWithSymbol(entry.getValue(), entry.getKey()),
                            NamedTextColor.YELLOW));
        }
        if (quote.refused() > 0) {
            confirmButton.lore(Component.text(quote.refused()
                    + " item(s) stay here: no price for them as they are", NamedTextColor.GRAY));
        }
        confirmButton.lore(Component.text("Click to confirm", NamedTextColor.GRAY));
        confirmButton.lore(Component.text("Final value is checked when you confirm", NamedTextColor.DARK_GRAY));

        inventory.setItem(49, confirmButton.build());

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
    }

    private void performSell() {
        SellTransaction.Sale sale = new SellTransaction(plugin)
                .sell(window(), player, slot -> inventory.setItem(slot, null));

        if (sale.isEmpty()) {
            player.sendMessage(Component.text(
                    "The sale could not be completed, so nothing was taken.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        for (Map.Entry<String, BigDecimal> entry : sale.paid().entrySet()) {
            player.sendMessage(Component.text()
                    .append(Component.text("Sold items for ", NamedTextColor.GREEN))
                    .append(Component.text(plugin.getEconomyManager().getFormattedWithSymbol(entry.getValue(), entry.getKey()), NamedTextColor.GOLD))
                    .build());
        }
        if (sale.unsold() > 0) {
            player.sendMessage(Component.text(sale.unsold()
                    + " item(s) could not be sold and were returned.", NamedTextColor.YELLOW));
        }

        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        player.closeInventory();
    }

    /** The 45 sell slots, in slot order. */
    private List<ItemStack> window() {
        List<ItemStack> contents = new ArrayList<>(45);
        for (int i = 0; i < 45; i++) {
            contents.add(inventory.getItem(i));
        }
        return contents;
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

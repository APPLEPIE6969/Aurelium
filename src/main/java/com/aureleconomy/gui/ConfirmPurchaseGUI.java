package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ConfirmPurchaseGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final AuctionItem auction;
    private final double amount;
    private final boolean isBid;

    public ConfirmPurchaseGUI(AurelEconomy plugin, Player player, AuctionItem auction, double amount, boolean isBid) {
        this.plugin = plugin;
        this.player = player;
        this.auction = auction;
        this.amount = amount;
        this.isBid = isBid;
        this.inventory = Bukkit.createInventory(this, 27, Component.text("Confirm " + (isBid ? "Bid" : "Purchase")));
        setupItems();
    }

    public void open() {
        player.openInventory(getInventory());
    }

    private void setupItems() {
        inventory.clear();

        // Target Item Information
        ItemStack display = auction.getItem().clone();
        display.editMeta(meta -> {
            meta.lore(List.of(
                    Component.text("Action: " + (isBid ? "Place Bid" : "Buy It Now"), NamedTextColor.GOLD),
                    Component.text("Cost: " + amount + " " + plugin.getConfig().getString("economy.currency-symbol"),
                            NamedTextColor.YELLOW)));
        });
        inventory.setItem(4, display);

        // Confirm Button
        inventory.setItem(11, new ItemBuilder(Material.LIME_WOOL)
                .name(Component.text("✔ CONFIRM", NamedTextColor.GREEN))
                .lore(Component.text("Click to proceed", NamedTextColor.GRAY)).build());

        // Cancel Button
        inventory.setItem(15, new ItemBuilder(Material.RED_WOOL)
                .name(Component.text("✖ CANCEL", NamedTextColor.RED))
                .lore(Component.text("Click to go back", NamedTextColor.GRAY)).build());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        // Prevent clicking in bottom inventory entirely
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);

        // Prevent double click dupe
        if (auction.isEnded()) {
            player.sendMessage(Component.text("Transaction already processing.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        if (slot == 11) { // Confirm
            if (plugin.getEconomyManager().has(player, amount)) {
                if (isBid) {
                    plugin.getAuctionManager().bid(auction, player.getUniqueId(), amount);
                    player.sendMessage(Component.text("Bid placed successfully!", NamedTextColor.GREEN));
                    new AuctionGUI(plugin, player, false).open();
                } else {
                    // Check capacity
                    if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), auction.getItem(),
                            auction.getItem().getAmount())) {
                        player.sendMessage(Component.text("Not enough space in inventory.", NamedTextColor.RED));
                        player.closeInventory();
                        return;
                    }

                    // Finalize Buy It Now
                    auction.setEnded(true);
                    plugin.getEconomyManager().withdraw(player, amount);
                    plugin.getAuctionManager().bid(auction, player.getUniqueId(), amount);
                    plugin.getAuctionManager().endAuction(auction);

                    player.getInventory().addItem(auction.getItem().clone());
                    plugin.getAuctionManager().markCollected(auction.getId());
                    player.sendMessage(Component.text("Successfully purchased " + auction.getItem().getType().name(),
                            NamedTextColor.GREEN));
                    new AuctionGUI(plugin, player, false).open();
                }
            } else {
                player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
                new AuctionGUI(plugin, player, false).open();
            }
        } else if (slot == 15) { // Cancel
            if (isBid) {
                new BidGUI(plugin, player, auction).open(); // Go back to configuring bid
            } else {
                new AuctionGUI(plugin, player, false).open(); // Go back to main AH
            }
        }
    }
}

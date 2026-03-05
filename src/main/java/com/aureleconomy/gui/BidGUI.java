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

public class BidGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final AuctionItem auction;
    private double currentBid;

    public BidGUI(AurelEconomy plugin, Player player, AuctionItem auction) {
        this.plugin = plugin;
        this.player = player;
        this.auction = auction;
        this.currentBid = auction.getPrice() * 1.1; // Default next bid is +10%
        this.inventory = Bukkit.createInventory(this, 27,
                Component.text("Place bid: " + auction.getItem().getType().name()));
        setupItems();
    }

    public void open() {
        player.openInventory(getInventory());
    }

    private void setupItems() {
        inventory.clear();

        // Item info
        ItemStack itemDisplay = auction.getItem().clone();
        itemDisplay.editMeta(meta -> {
            meta.lore(List.of(
                    Component.text("Current: " + auction.getPrice(), NamedTextColor.GOLD),
                    Component.text("Your Bid: " + currentBid, NamedTextColor.YELLOW)));
        });
        inventory.setItem(4, itemDisplay);

        // Quick bid buttons
        inventory.setItem(11, new ItemBuilder(Material.LIME_STAINED_GLASS_PANE)
                .name(Component.text("+10%", NamedTextColor.GREEN))
                .lore(Component.text("New Bid: " + (auction.getPrice() * 1.1), NamedTextColor.GRAY)).build());

        inventory.setItem(12, new ItemBuilder(Material.GREEN_STAINED_GLASS_PANE)
                .name(Component.text("+50%", NamedTextColor.GREEN))
                .lore(Component.text("New Bid: " + (auction.getPrice() * 1.5), NamedTextColor.GRAY)).build());

        inventory.setItem(13, new ItemBuilder(Material.EMERALD_BLOCK)
                .name(Component.text("+100%", NamedTextColor.GREEN))
                .lore(Component.text("New Bid: " + (auction.getPrice() * 2.0), NamedTextColor.GRAY)).build());

        inventory.setItem(15, new ItemBuilder(Material.NAME_TAG)
                .name(Component.text("Custom Bid", NamedTextColor.AQUA))
                .lore(Component.text("Enter amount in chat", NamedTextColor.GRAY)).build());

        // Confirm / Cancel
        inventory.setItem(22, new ItemBuilder(Material.GREEN_WOOL)
                .name(Component.text("Confirm Bid", NamedTextColor.GREEN))
                .lore(Component.text("Amount: " + currentBid, NamedTextColor.GRAY)).build());

        inventory.setItem(18, new ItemBuilder(Material.BARRIER)
                .name(Component.text("Back to AH", NamedTextColor.RED)).build());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void handleClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();

        // Prevent clicking in bottom inventory entirely
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            event.setCancelled(true);
            return;
        }

        int slot = event.getSlot();
        event.setCancelled(true);

        switch (slot) {
            case 11 -> currentBid = auction.getPrice() * 1.1;
            case 12 -> currentBid = auction.getPrice() * 1.5;
            case 13 -> currentBid = auction.getPrice() * 2.0;
            case 15 -> {
                player.closeInventory();
                player.sendMessage(
                        Component.text("Type your custom bid amount in chat (or type 'cancel'):",
                                NamedTextColor.YELLOW));
                plugin.getChatPromptManager().prompt(player, (input) -> {
                    if (input.equalsIgnoreCase("cancel")) {
                        player.sendMessage(Component.text("Cancelled.", NamedTextColor.RED));
                        new BidGUI(plugin, player, auction).open();
                        return;
                    }
                    try {
                        double bidAmount = Double.parseDouble(input);
                        if (bidAmount <= auction.getPrice()) {
                            player.sendMessage(Component.text("Bid must be strictly higher than the current bid!",
                                    NamedTextColor.RED));
                            new BidGUI(plugin, player, auction).open();
                            return;
                        }
                        if (plugin.getEconomyManager().has(player, bidAmount)) {
                            plugin.getAuctionManager().bid(auction, player.getUniqueId(), bidAmount);
                            player.sendMessage(Component.text("Bid placed successfully!", NamedTextColor.GREEN));
                            new AuctionGUI(plugin, player, false).open();
                        } else {
                            player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
                            new BidGUI(plugin, player, auction).open();
                        }
                    } catch (Exception e) {
                        player.sendMessage(Component.text("Invalid amount.", NamedTextColor.RED));
                        new BidGUI(plugin, player, auction).open();
                    }
                });
                return;
            }
            case 18 -> {
                new AuctionGUI(plugin, player, false).open();
                return;
            }
            case 22 -> {
                if (plugin.getEconomyManager().has(player, currentBid)) {
                    new ConfirmPurchaseGUI(plugin, player, auction, currentBid, true).open();
                } else {
                    player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
                }
                return;
            }
        }
        setupItems();
    }
}

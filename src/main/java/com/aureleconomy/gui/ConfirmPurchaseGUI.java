package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.utils.ItemBuilder;
import com.aureleconomy.utils.InventoryUtils;
import java.math.BigDecimal;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ConfirmPurchaseGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final AuctionItem auction;
    private final BigDecimal amount;
    private final boolean isBid;

    public ConfirmPurchaseGUI(AurelEconomy plugin, Player player, AuctionItem auction, BigDecimal amount, boolean isBid) {
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

        ItemStack display = auction.getItem().clone();
        display.editMeta(meta -> {
            meta.lore(List.of(
                    Component.text("Action: " + (isBid ? "Place Bid" : "Buy It Now"), NamedTextColor.GOLD),
                    Component.text("Cost: " + plugin.getEconomyManager().getFormattedWithSymbol(amount, auction.getCurrency()), NamedTextColor.YELLOW)));
        });
        inventory.setItem(4, display);

        inventory.setItem(11, new ItemBuilder(Material.LIME_WOOL)
                .name(Component.text("✔ CONFIRM", NamedTextColor.GREEN))
                .lore(Component.text("Click to proceed", NamedTextColor.GRAY)).build());
        inventory.setItem(15, new ItemBuilder(Material.RED_WOOL)
                .name(Component.text("✖ CANCEL", NamedTextColor.RED))
                .lore(Component.text("Click to go back", NamedTextColor.GRAY)).build());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) return;

        int slot = event.getSlot();

        if (auction.isEnded()) {
            player.sendMessage(Component.text("Transaction already processing or expired.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        if (slot == 11) { 
            handleConfirm(player);
        } else if (slot == 15) { 
            if (isBid) {
                new BidGUI(plugin, player, auction).open();
            } else {
                new AuctionGUI(plugin, player, false).open();
            }
        }
    }

    private void handleConfirm(Player player) {
        if (!plugin.getEconomyManager().has(player, amount, auction.getCurrency())) {
            player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
            new AuctionGUI(plugin, player, false).open();
            return;
        }

        if (isBid) {
            // bid() charges the bidder now (C1), so a refusal has to stop here,
            // not after a success message.
            AuctionManager.BidResult result = plugin
                    .getAuctionManager().bid(auction, player.getUniqueId(), amount);
            if (result != AuctionManager.BidResult.SUCCESS) {
                player.sendMessage(Component.text(result.playerMessage(), NamedTextColor.RED));
                new AuctionGUI(plugin, player, false).open();
                return;
            }
            player.sendMessage(Component.text("Bid placed successfully!", NamedTextColor.GREEN));
            new AuctionGUI(plugin, player, false).open();
        } else {
            if (!InventoryUtils.hasSpace(player.getInventory(), auction.getItem(), auction.getItem().getAmount())) {
                player.sendMessage(Component.text("Not enough space in inventory.", NamedTextColor.RED));
                player.closeInventory();
                return;
            }

            if (plugin.getAuctionManager().claimAuctionAtomic(auction.getId())) {
                // The separate withdraw that used to sit here is gone: bid()
                // charges the buyer itself now (C1), and charging here as well
                // would take the same purchase out of the balance twice.
                AuctionManager.BidResult result = plugin
                        .getAuctionManager().bid(auction, player.getUniqueId(), amount);
                if (result != AuctionManager.BidResult.SUCCESS) {
                    // No payout, no item and no collection: the bid is the whole
                    // sale, and the sale did not happen.
                    player.sendMessage(Component.text(result.playerMessage(), NamedTextColor.RED));
                    new AuctionGUI(plugin, player, false).open();
                    return;
                }

                plugin.getAuctionManager().endAuction(auction);

                player.getInventory().addItem(auction.getItem().clone());
                plugin.getAuctionManager().markCollected(auction.getId());
                
                player.sendMessage(Component.text("Successfully purchased " + auction.getItem().getType().name(), NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("This item was already purchased or expired!", NamedTextColor.RED));
            }
            new AuctionGUI(plugin, player, false).open();
        }
    }
}

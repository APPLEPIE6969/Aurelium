package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.Offer;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class OffersGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;

    public OffersGUI(AurelEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Incoming Offers"));
        setupItems();
    }

    public void open() {
        player.openInventory(getInventory());
    }

    private void setupItems() {
        inventory.clear();
        inventory.setItem(49,
                new ItemBuilder(Material.BARRIER).name(Component.text("Back to AH", NamedTextColor.RED)).build());

        // Get offers for player's auctions
        plugin.getAuctionManager().getOffersForSeller(player.getUniqueId(), offers -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Offer offer : offers) {
                    if (!offer.getStatus().equals("PENDING"))
                        continue;

                    AuctionItem ai = plugin.getAuctionManager().getAuctionById(offer.getAuctionId());
                    if (ai == null)
                        continue;

                    ItemStack display = ai.getItem().clone();
                    String bidderName = Bukkit.getOfflinePlayer(offer.getBidder()).getName();

                    display.editMeta(meta -> {
                        meta.lore(List.of(
                                Component.text("Offer Amount: " + offer.getAmount(), NamedTextColor.GOLD),
                                Component.text("From: " + bidderName, NamedTextColor.GRAY),
                                Component.empty(),
                                Component.text("Left-Click to ACCEPT", NamedTextColor.GREEN),
                                Component.text("Right-Click to DECLINE", NamedTextColor.RED)));
                        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "offer_id"),
                                PersistentDataType.INTEGER, offer.getId());
                    });

                    inventory.addItem(display);
                }
            });
        });
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public synchronized void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR)
            return;

        Player player = (Player) event.getWhoClicked();
        // Prevent clicking in bottom inventory entirely
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            return;
        }

        if (event.getSlot() == 49) {
            new AuctionGUI(plugin, player, false).open();
            return;
        }

        Integer offerId = clicked.getItemMeta().getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, "offer_id"), PersistentDataType.INTEGER);

        if (offerId != null) {
            if (event.isLeftClick()) {
                plugin.getAuctionManager().acceptOffer(offerId, player);
            } else if (event.isRightClick()) {
                plugin.getAuctionManager().declineOffer(offerId, player);
            }
            setupItems();
        }
    }
}

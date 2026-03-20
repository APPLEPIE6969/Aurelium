package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.Offer;
import com.aureleconomy.auction.OfferStatus;
import com.aureleconomy.utils.ItemBuilder;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * GUI for viewing and managing incoming private offers on player's auctions.
 * Updated to use OfferStatus enum and BigDecimal for currency.
 */
public class OffersGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final NamespacedKey offerIdKey;

    public OffersGUI(AurelEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.offerIdKey = new NamespacedKey(plugin, "offer_id");
        this.inventory = Bukkit.createInventory(this, 54, Component.text("Incoming Offers"));
        setupItems();
    }

    public void open() {
        player.openInventory(getInventory());
    }

    private void setupItems() {
        inventory.clear();
        inventory.setItem(49, new ItemBuilder(Material.BARRIER).name(Component.text("Back to AH", NamedTextColor.RED)).build());

        // Asynchronously fetch offers 
        plugin.getAuctionManager().getOffersForSeller(player.getUniqueId(), offers -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Offer offer : offers) {
                    // Only show PENDING offers
                    if (offer.getStatus() != OfferStatus.PENDING) continue;

                    AuctionItem ai = plugin.getAuctionManager().getAuctionById(offer.getAuctionId());
                    if (ai == null) continue;

                    ItemStack display = ai.getItem().clone();
                    String bidderName = Bukkit.getOfflinePlayer(offer.getBidder()).getName();
                    String amountFormatted = plugin.getEconomyManager().format(offer.getAmount(), ai.getCurrency());

                    display.editMeta(meta -> {
                        meta.lore(List.of(
                                Component.text("Offer Amount: " + amountFormatted, NamedTextColor.GOLD),
                                Component.text("From: " + (bidderName != null ? bidderName : "Unknown"), NamedTextColor.GRAY),
                                Component.empty(),
                                Component.text("Left-Click to ACCEPT", NamedTextColor.GREEN),
                                Component.text("Right-Click to DECLINE", NamedTextColor.RED)));
                        meta.getPersistentDataContainer().set(offerIdKey, PersistentDataType.INTEGER, offer.getId());
                    });

                    if (inventory.firstEmpty() != -1) {
                        inventory.addItem(display);
                    }
                }
            });
        });
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

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (event.getSlot() == 49) {
            new AuctionGUI(plugin, player, false).open();
            return;
        }

        Integer offerId = clicked.getItemMeta().getPersistentDataContainer().get(offerIdKey, PersistentDataType.INTEGER);
        if (offerId != null) {
            if (event.isLeftClick()) {
                plugin.getAuctionManager().acceptOffer(offerId, player);
                setupItems(); // Refresh locally after action
            } else if (event.isRightClick()) {
                plugin.getAuctionManager().declineOffer(offerId, player);
                setupItems(); // Refresh locally after action
            }
        }
    }
}

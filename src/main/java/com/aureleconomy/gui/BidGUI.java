package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.utils.ItemBuilder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * GUI for placing bids on auctions.
 * Refactored to use BigDecimal for precise bid calculations and multipliers.
 */
public class BidGUI extends GUIHolder {

    private static final BigDecimal MULTIPLIER_10 = new BigDecimal("1.10");
    private static final BigDecimal MULTIPLIER_50 = new BigDecimal("1.50");
    private static final BigDecimal MULTIPLIER_100 = new BigDecimal("2.00");

    private final AurelEconomy plugin;
    private final Player player;
    private final AuctionItem auction;
    private BigDecimal currentBid;

    public BidGUI(AurelEconomy plugin, Player player, AuctionItem auction) {
        this.plugin = plugin;
        this.player = player;
        this.auction = auction;
        // Default next bid is +10%
        this.currentBid = auction.getPrice().multiply(MULTIPLIER_10).setScale(2, RoundingMode.HALF_UP);
        this.inventory = Bukkit.createInventory(this, 27,
                Component.text("Place bid: " + auction.getItem().getType().name()));
        setupItems();
    }

    public void open() {
        player.openInventory(getInventory());
    }

    private void setupItems() {
        inventory.clear();

        // Item info display
        ItemStack itemDisplay = auction.getItem().clone();
        itemDisplay.editMeta(meta -> {
            meta.lore(List.of(
                    Component.text("Current: " + plugin.getEconomyManager().getFormattedWithSymbol(auction.getPrice(), auction.getCurrency()), NamedTextColor.GOLD),
                    Component.text("Your Bid: " + plugin.getEconomyManager().getFormattedWithSymbol(currentBid, auction.getCurrency()), NamedTextColor.YELLOW)));
        });
        inventory.setItem(4, itemDisplay);

        // Quick bid percentage buttons
        addBidButton(11, "+10%", MULTIPLIER_10);
        addBidButton(12, "+50%", MULTIPLIER_50);
        addBidButton(13, "+100%", MULTIPLIER_100);

        inventory.setItem(15, new ItemBuilder(Material.NAME_TAG)
                .name(Component.text("Custom Bid", NamedTextColor.AQUA))
                .lore(Component.text("Enter amount in chat", NamedTextColor.GRAY)).build());

        // Confirm / Back buttons
        inventory.setItem(22, new ItemBuilder(Material.GREEN_WOOL)
                .name(Component.text("Confirm Bid", NamedTextColor.GREEN))
                .lore(Component.text("Amount: " + plugin.getEconomyManager().getFormattedWithSymbol(currentBid, auction.getCurrency()), NamedTextColor.GRAY)).build());

        inventory.setItem(18, new ItemBuilder(Material.BARRIER)
                .name(Component.text("Back to AH", NamedTextColor.RED)).build());
    }

    private void addBidButton(int slot, String label, BigDecimal multiplier) {
        BigDecimal bid = auction.getPrice().multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
        inventory.setItem(slot, net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Component.text(label)).contains("10") ?
                new ItemBuilder(Material.LIME_STAINED_GLASS_PANE).name(Component.text(label, NamedTextColor.GREEN))
                        .lore(Component.text("New Bid: " + plugin.getEconomyManager().getFormattedWithSymbol(bid, auction.getCurrency()), NamedTextColor.GRAY)).build() :
                new ItemBuilder(slot == 12 ? Material.GREEN_STAINED_GLASS_PANE : Material.EMERALD_BLOCK)
                        .name(Component.text(label, NamedTextColor.GREEN))
                        .lore(Component.text("New Bid: " + plugin.getEconomyManager().getFormattedWithSymbol(bid, auction.getCurrency()), NamedTextColor.GRAY)).build()
        );
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

        switch (slot) {
            case 11 -> { currentBid = auction.getPrice().multiply(MULTIPLIER_10).setScale(2, RoundingMode.HALF_UP); setupItems(); }
            case 12 -> { currentBid = auction.getPrice().multiply(MULTIPLIER_50).setScale(2, RoundingMode.HALF_UP); setupItems(); }
            case 13 -> { currentBid = auction.getPrice().multiply(MULTIPLIER_100).setScale(2, RoundingMode.HALF_UP); setupItems(); }
            case 15 -> handleCustomBid(player);
            case 18 -> new AuctionGUI(plugin, player, false).open();
            case 22 -> {
                if (plugin.getEconomyManager().has(player, currentBid, auction.getCurrency())) {
                    new ConfirmPurchaseGUI(plugin, player, auction, currentBid, true).open();
                } else {
                    player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
                }
            }
        }
    }

    private void handleCustomBid(Player player) {
        player.closeInventory();
        player.sendMessage(Component.text("Type your custom bid amount in chat (or 'cancel'):", NamedTextColor.YELLOW));
        plugin.getChatPromptManager().prompt(player, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            try {
                BigDecimal bidAmount = new BigDecimal(input).setScale(2, RoundingMode.HALF_UP);
                if (bidAmount.compareTo(auction.getPrice()) <= 0) {
                    player.sendMessage(Component.text("Bid must be strictly higher than current price!", NamedTextColor.RED));
                    open();
                    return;
                }
                if (plugin.getEconomyManager().has(player, bidAmount, auction.getCurrency())) {
                    plugin.getAuctionManager().bid(auction, player.getUniqueId(), bidAmount);
                    player.sendMessage(Component.text("Bid placed successfully!", NamedTextColor.GREEN));
                    new AuctionGUI(plugin, player, false).open();
                } else {
                    player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
                    open();
                }
            } catch (Exception e) {
                player.sendMessage(Component.text("Invalid amount.", NamedTextColor.RED));
                open();
            }
        });
    }
}

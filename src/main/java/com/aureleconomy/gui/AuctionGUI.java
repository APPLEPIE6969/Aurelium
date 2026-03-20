package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.utils.ItemBuilder;
import java.math.BigDecimal;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Main GUI for the Auction House and Collection Bin.
 * Updated to use BigDecimal for all financial calculations and formatting.
 */
public class AuctionGUI extends GUIHolder {

    private static final String KEY_AUCTION_ID = "auction_id";
    private static final long WEEK_MILLIS = 86400000L * 7;

    private final AurelEconomy plugin;
    private final Player player;
    private final boolean isCollectionBin;
    private String searchQuery = null;
    private final NamespacedKey auctionIdKey;

    public AuctionGUI(AurelEconomy plugin, Player player, boolean isCollectionBin) {
        this.plugin = plugin;
        this.player = player;
        this.isCollectionBin = isCollectionBin;
        this.auctionIdKey = new NamespacedKey(plugin, KEY_AUCTION_ID);
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text(isCollectionBin ? "Collection Bin" : "Auction House"));
        setupItems();
    }

    public void open() {
        player.openInventory(getInventory());
    }

    public void refresh() {
        inventory.clear();
        setupItems();
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query;
        refresh();
    }

    public static void refreshAllViewers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof AuctionGUI gui) {
                if (!gui.isCollectionBin) {
                    gui.refresh();
                }
            }
        }
    }

    private void setupItems() {
        // Navigation / Utility items
        inventory.setItem(49,
                new ItemBuilder(Material.BARRIER).name(Component.text("Close", NamedTextColor.RED)).build());

        if (isCollectionBin) {
            setupCollectionBin();
        } else {
            setupAuctionHouse();
        }
    }

    private void setupCollectionBin() {
        List<AuctionItem> items = plugin.getAuctionManager().getCollectionBin(player.getUniqueId());
        for (AuctionItem ai : items) {
            if (inventory.firstEmpty() == -1)
                break;

            ItemStack display = ai.getItem().clone();
            display.editMeta(meta -> {
                meta.lore(List.of(Component.text("Click to Collect", NamedTextColor.GREEN)));
                meta.getPersistentDataContainer().set(auctionIdKey, PersistentDataType.INTEGER, ai.getId());
            });
            inventory.addItem(display);
        }
    }

    private void setupAuctionHouse() {
        // Auction House side buttons
        inventory.setItem(51, new ItemBuilder(Material.EMERALD)
                .name(Component.text("Sell Item", NamedTextColor.GREEN))
                .lore(Component.text("Click to list an item", NamedTextColor.GRAY)).build());

        inventory.setItem(53, new ItemBuilder(Material.CHEST)
                .name(Component.text("Collection Bin", NamedTextColor.GOLD)).build());

        inventory.setItem(52, new ItemBuilder(Material.PAPER)
                .name(Component.text("Manage Offers", NamedTextColor.GOLD))
                .lore(Component.text("View and Manage private offers", NamedTextColor.GRAY)).build());

        inventory.setItem(45, new ItemBuilder(Material.BOOK)
                .name(Component.text("Auction Info", NamedTextColor.AQUA))
                .lore(Component.text("• Click to Bid", NamedTextColor.GRAY),
                        Component.text("• Right-Click to MAKE OFFER", NamedTextColor.GRAY),
                        Component.text("• Shift+Right-Click to CANCEL (Yours)", NamedTextColor.GRAY))
                .build());

        inventory.setItem(46, new ItemBuilder(Material.COMPASS)
                .name(Component.text(searchQuery != null ? "Change Search: " + searchQuery : "Search Auction",
                        NamedTextColor.AQUA))
                .lore(Component.text("Find items by name", NamedTextColor.GRAY)).build());

        List<AuctionItem> auctions = plugin.getAuctionManager().getActiveAuctions();
        if (searchQuery != null) {
            String query = searchQuery.toLowerCase();
            auctions = auctions.stream().filter(ai -> {
                String name = ai.getItem().getType().name().toLowerCase();
                if (ai.getItem().hasItemMeta() && ai.getItem().getItemMeta().hasDisplayName()) {
                    name = PlainTextComponentSerializer.plainText().serialize(ai.getItem().getItemMeta().displayName())
                            .toLowerCase();
                }
                return name.contains(query);
            }).toList();
        }

        for (AuctionItem ai : auctions) {
            if (inventory.firstEmpty() == -1)
                break;

            ItemStack display = ai.getItem().clone();
            String sellerName = Bukkit.getOfflinePlayer(ai.getSeller()).getName();
            String priceFormatted = plugin.getEconomyManager().getFormattedWithSymbol(ai.getPrice(), ai.getCurrency());
            String priceLine = (ai.isBin() ? "Buy It Now: " : "Current Bid: ") + priceFormatted;
            String timeLeft = formatTime(ai.getExpiration() - System.currentTimeMillis());

            display.editMeta(meta -> {
                List<Component> lore = new java.util.ArrayList<>();
                lore.add(Component.text(priceLine, NamedTextColor.GOLD));

                if (ai.getSeller().equals(player.getUniqueId())) {
                    lore.add(Component.text("Seller: You", NamedTextColor.GRAY));
                    lore.add(Component.text("Time Left: " + timeLeft, NamedTextColor.GRAY));
                    lore.add(Component.text("ID: #" + ai.getId(), NamedTextColor.DARK_GRAY));
                    lore.add(Component.empty());
                    lore.add(Component.text("Shift+Right-Click to Cancel", NamedTextColor.RED));
                } else {
                    lore.add(Component.text("Seller: " + (sellerName != null ? sellerName : "Unknown"),
                            NamedTextColor.GRAY));
                    lore.add(Component.text("Time Left: " + timeLeft, NamedTextColor.GRAY));
                    lore.add(Component.text("ID: #" + ai.getId(), NamedTextColor.DARK_GRAY));
                    lore.add(Component.empty());
                    lore.add(Component.text(ai.isBin() ? "Click to Buy" : "Click to BID", NamedTextColor.YELLOW));
                    lore.add(Component.text("Right-Click to MAKE OFFER", NamedTextColor.GOLD));
                }
                meta.lore(lore);
                meta.getPersistentDataContainer().set(auctionIdKey, PersistentDataType.INTEGER, ai.getId());
            });
            inventory.addItem(display);
        }
    }

    private String formatTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long minutes = seconds / 60;
        long hours = minutes / 60;
        return String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60);
    }

    @Override
    public synchronized void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();

        // Anti-flicker: Clear client-side predicted moves for shift-clicks
        if (event.isShiftClick()) {
            player.updateInventory();
        }

        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR)
            return;
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory()))
            return;

        if (slot == 49) {
            if (searchQuery != null && !isCollectionBin) {
                searchQuery = null;
                refresh();
            } else {
                player.closeInventory();
            }
            return;
        }

        if (slot == 46 && !isCollectionBin) {
            promptSearch();
            return;
        }

        if (slot == 53) {
            new AuctionGUI(plugin, player, true).open();
            return;
        }

        if (slot == 52) {
            new OffersGUI(plugin, player).open();
            return;
        }

        if (slot == 51 && !isCollectionBin) {
            handleSellPrompt(player);
            return;
        }

        if (isCollectionBin) {
            handleCollection(player, clicked, slot);
        } else {
            handleAuctionInteraction(player, clicked, event);
        }
    }

    private void handleSellPrompt(Player player) {
        player.closeInventory();
        player.sendMessage(Component.text("Hold the item in your main hand and type the price in chat (or 'cancel'):",
                NamedTextColor.YELLOW));

        plugin.getChatPromptManager().prompt(player, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            try {
                BigDecimal price = new BigDecimal(input);
                if (price.compareTo(BigDecimal.ZERO) <= 0)
                    throw new NumberFormatException();

                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR) {
                    player.sendMessage(Component.text("You must hold an item to sell it!", NamedTextColor.RED));
                    open();
                    return;
                }

                BigDecimal fee = BigDecimal.valueOf(plugin.getConfig().getDouble("auction.listing-fee", 10.0));
                if (plugin.getEconomyManager().has(player, fee)) {
                    plugin.getEconomyManager().withdraw(player, fee);
                    plugin.getAuctionManager().listAuction(player.getUniqueId(), hand.clone(), price,
                            plugin.getEconomyManager().getDefaultCurrency(), true, WEEK_MILLIS, fee);
                    player.getInventory().setItemInMainHand(null);
                    player.sendMessage(
                            Component.text("Item listed for " + plugin.getEconomyManager().getFormattedWithSymbol(price,
                                    plugin.getEconomyManager().getDefaultCurrency()), NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text(
                            "You cannot afford the " + plugin.getEconomyManager().getFormattedWithSymbol(fee,
                                    plugin.getEconomyManager().getDefaultCurrency()) + " listing fee.",
                            NamedTextColor.RED));
                }
                open();
            } catch (Exception e) {
                player.sendMessage(Component.text("Invalid price.", NamedTextColor.RED));
                open();
            }
        });
    }

    private void handleCollection(Player player, ItemStack clicked, int slot) {
        Integer id = clicked.getItemMeta().getPersistentDataContainer().get(auctionIdKey, PersistentDataType.INTEGER);
        if (id == null)
            return;

        ItemStack give = clicked.clone();
        give.editMeta(meta -> {
            meta.lore(null);
            meta.getPersistentDataContainer().remove(auctionIdKey);
        });

        // Strict Pre-Check: Never drop on ground
        if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), give, give.getAmount())) {
            player.sendMessage(Component.text("Cannot collect: Your inventory is full!", NamedTextColor.RED));
            return;
        }

        // Atomic check: Only proceed if this thread successfully marks the item as
        // collected
        // This prevents item duplication from rapid clicking
        if (!plugin.getAuctionManager().markCollectedAtomic(id)) {
            player.sendMessage(Component.text("This item has already been collected!", NamedTextColor.RED));
            refresh();
            return;
        }

        // Proceed with removal and collection
        inventory.setItem(slot, null);
        player.getInventory().addItem(give);

        player.sendMessage(Component.text("Collected item!", NamedTextColor.GREEN));
        refresh();
    }

    private void handleAuctionInteraction(Player player, ItemStack clicked, InventoryClickEvent event) {
        Integer id = clicked.getItemMeta().getPersistentDataContainer().get(auctionIdKey, PersistentDataType.INTEGER);
        if (id == null)
            return;

        AuctionItem ai = plugin.getAuctionManager().getAuctionById(id);
        if (ai == null) {
            player.sendMessage(Component.text("Auction expired or doesn't exist.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }

        if (ai.getSeller().equals(player.getUniqueId())) {
            if (event.isShiftClick() && event.isRightClick()) {
                plugin.getAuctionManager().cancelAuction(ai, player);
            } else {
                player.sendMessage(Component.text("Shift+Right-Click to CANCEL your auction.", NamedTextColor.YELLOW));
            }
            return;
        }

        if (ai.isBin()) {
            new ConfirmPurchaseGUI(plugin, player, ai, ai.getPrice(), false).open();
        } else {
            if (event.isRightClick()) {
                promptOffer(player, ai);
            } else {
                new BidGUI(plugin, player, ai).open();
            }
        }
    }

    private void promptOffer(Player player, AuctionItem ai) {
        player.closeInventory();
        player.sendMessage(Component.text("Type your offer amount in chat (or 'cancel'):", NamedTextColor.GOLD));
        plugin.getChatPromptManager().prompt(player, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            try {
                BigDecimal offerAmount = new BigDecimal(input);
                if (offerAmount.compareTo(BigDecimal.ZERO) <= 0)
                    throw new NumberFormatException();

                plugin.getAuctionManager().makeOffer(ai, player, offerAmount);
                open();
            } catch (Exception e) {
                player.sendMessage(Component.text("Invalid amount.", NamedTextColor.RED));
                open();
            }
        });
    }

    private void promptSearch() {
        player.closeInventory();
        player.sendMessage(Component.text("Type the item name to search (or 'cancel'):", NamedTextColor.AQUA));
        plugin.getChatPromptManager().prompt(player, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            this.searchQuery = input;
            open();
        });
    }
}

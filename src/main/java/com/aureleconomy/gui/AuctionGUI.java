package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.utils.ItemBuilder;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

 /**
  * Rebuilds every open Auction House GUI. Callers run on both the main thread and
  * async database tasks, so the rebuild is bounced onto the main thread first:
  * mutating a viewed inventory off-thread is not pushed to the client reliably.
  */
 public static void refreshAllViewers(AurelEconomy plugin) {
 if (!Bukkit.isPrimaryThread()) {
  if (plugin.isEnabled()) {
   Bukkit.getScheduler().runTask(plugin, () -> refreshAllViewers(plugin));
  }
  return;
 }

 for (Player p : Bukkit.getOnlinePlayers()) {
  if (p.getOpenInventory().getTopInventory().getHolder() instanceof AuctionGUI gui) {
   if (!gui.isCollectionBin) {
    gui.refresh();
   }
  }
 }
 }

 private void setupItems() {
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
 .lore(Component.text("Click to Buy/Bid", NamedTextColor.GRAY),
 Component.text("Right-Click to MAKE OFFER", NamedTextColor.GRAY),
 Component.text("Shift+Right-Click to CANCEL (Yours)", NamedTextColor.GRAY),
 Component.text("UNIT mode: Click to buy per-unit", NamedTextColor.DARK_AQUA))
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
   final String priceLine = (ai.isBin() ? "Buy It Now: " : "Current Bid: ") + priceFormatted
  + (ai.getPurchaseMode() == AuctionItem.PurchaseMode.UNIT ? " each" : "");
 String timeLeft = formatTime(ai.getExpiration() - System.currentTimeMillis());

 display.editMeta(meta -> {
 List<Component> lore = new java.util.ArrayList<>();
 lore.add(Component.text(priceLine, NamedTextColor.GOLD));

 if (ai.getPurchaseMode() == AuctionItem.PurchaseMode.UNIT) {
 lore.add(Component.text("Mode: UNIT (x" + ai.getAvailableQuantity() + " available)", NamedTextColor.BLUE));
 } else {
 lore.add(Component.text("Mode: STACK", NamedTextColor.GOLD));
 }

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
 if (ai.getPurchaseMode() == AuctionItem.PurchaseMode.UNIT) {
 lore.add(Component.text("Click to Buy (per unit)", NamedTextColor.YELLOW));
 } else {
 lore.add(Component.text(ai.isBin() ? "Click to Buy" : "Click to BID", NamedTextColor.YELLOW));
 }
 if (ai.getPurchaseMode() != AuctionItem.PurchaseMode.UNIT) {
 lore.add(Component.text("Right-Click to MAKE OFFER", NamedTextColor.GOLD));
 }
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

 BigDecimal price;
 try {
  price = new BigDecimal(input);
 } catch (NumberFormatException e) {
  player.sendMessage(Component.text("Invalid price.", NamedTextColor.RED));
  open();
  return;
 }
 if (price.compareTo(BigDecimal.ZERO) <= 0) {
  player.sendMessage(Component.text("Invalid price.", NamedTextColor.RED));
  open();
  return;
 }

 ItemStack hand = player.getInventory().getItemInMainHand();
 if (hand == null || hand.getType() == Material.AIR) {
  player.sendMessage(Component.text("You must hold an item to sell it!", NamedTextColor.RED));
  open();
  return;
 }

 if (plugin.getMarketManager().isBlacklisted(hand.getType())) {
  player.sendMessage(Component.text("This item is blacklisted.", NamedTextColor.RED));
  open();
  return;
 }

 String currency = plugin.getEconomyManager().getDefaultCurrency();
 BigDecimal fee = calculateListingFee(price, WEEK_MILLIS);

 if (!plugin.getEconomyManager().has(player, fee, currency)) {
  player.sendMessage(Component.text(
  "You cannot afford the " + plugin.getEconomyManager().getFormattedWithSymbol(fee, currency) + " listing fee.",
  NamedTextColor.RED));
  open();
  return;
 }

 AuctionItem.PurchaseMode mode;
 try {
  mode = AuctionItem.PurchaseMode.valueOf(
  plugin.getConfig().getString("auction-house.purchase-mode", "STACK").toUpperCase());
 } catch (IllegalArgumentException e) {
  player.sendMessage(Component.text("Invalid auction-house.purchase-mode in config.", NamedTextColor.RED));
  open();
  return;
 }

 plugin.getEconomyManager().withdraw(player, fee, currency);

// listAuction persists on an async task, so the listing only reaches activeAuctions
  // (and therefore the GUI) later. Reopen from the completion callback instead of
  // rebuilding the inventory now, otherwise the new listing is missing from the slots.
  ItemStack toList = hand.clone();
  plugin.getAuctionManager().listAuction(player.getUniqueId(), toList, price, currency, true,
  WEEK_MILLIS, fee, mode, (listed) -> {
  if (listed == null) {
   plugin.getEconomyManager().deposit(player, fee, currency);
   player.sendMessage(
   Component.text("Could not list the item. Your item was not taken and your listing fee has been refunded.", NamedTextColor.RED));
  } else {
   // Clear the hand only now that the listing is durable. Clearing it before the
   // insert lost the item outright whenever the insert failed (issue #33).
   com.aureleconomy.utils.InventoryUtils.clearMainHandIfSimilar(player, toList);
   player.sendMessage(Component.text("Item listed for "
   + plugin.getEconomyManager().getFormattedWithSymbol(price, currency)
   + " (Fee: " + plugin.getEconomyManager().getFormattedWithSymbol(fee, currency) + ")",
   NamedTextColor.GREEN));
  }
refresh();
   open();
   });
  });
  }

  /**
   * Listing fee for a given price and duration, matching the {@code /ah sell} command:
  * a percentage of the price, scaled up 5% per day beyond the first.
  */
 private BigDecimal calculateListingFee(BigDecimal price, long durationMillis) {
 BigDecimal feeRate = BigDecimal.valueOf(plugin.getConfig().getDouble("auction-house.listing-fee-percent", 2.0))
 .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
 BigDecimal days = BigDecimal.valueOf(durationMillis)
 .divide(BigDecimal.valueOf(86400000L), 4, RoundingMode.HALF_UP);
 BigDecimal scalingMultiplier = BigDecimal.ONE
 .add(days.subtract(BigDecimal.ONE).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(0.05)));
 return price.multiply(feeRate).multiply(scalingMultiplier).setScale(2, RoundingMode.HALF_UP);
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

 if (!com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), give, give.getAmount())) {
 player.sendMessage(Component.text("Cannot collect: Your inventory is full!", NamedTextColor.RED));
 return;
 }

 if (!plugin.getAuctionManager().markCollectedAtomic(id)) {
 player.sendMessage(Component.text("This item has already been collected!", NamedTextColor.RED));
 refresh();
 return;
 }

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
 if (ai.getPurchaseMode() == AuctionItem.PurchaseMode.UNIT) {
 // UNIT mode: prompt quantity via chat
 promptUnitPurchase(player, ai);
 } else {
 new ConfirmPurchaseGUI(plugin, player, ai, ai.getPrice(), false).open();
 }
 } else {
 // UNIT mode listings don't support bidding — only direct per-unit purchase
 if (ai.getPurchaseMode() == AuctionItem.PurchaseMode.UNIT) {
 player.sendMessage(Component.text("This is a UNIT-mode listing. Click to buy per-unit.", NamedTextColor.BLUE));
 promptUnitPurchase(player, ai);
 return;
 }
 if (event.isRightClick()) {
 promptOffer(player, ai);
 } else {
 new BidGUI(plugin, player, ai).open();
 }
 }
 }

 private void promptUnitPurchase(Player player, AuctionItem ai) {
 player.closeInventory();
 int available = ai.getAvailableQuantity();
 player.sendMessage(Component.text(
 "This listing is UNIT mode (" + available + " available at "
 + plugin.getEconomyManager().getFormattedWithSymbol(ai.getPricePerUnit(), ai.getCurrency()) + " each).",
 NamedTextColor.BLUE));
 player.sendMessage(Component.text("Type the quantity you want to buy (or 'cancel'):", NamedTextColor.YELLOW));

 plugin.getChatPromptManager().prompt(player, (input) -> {
 if (input.equalsIgnoreCase("cancel")) {
 open();
 return;
 }
 try {
 int qty = Integer.parseInt(input);
 if (qty < 1 || qty > available) {
 player.sendMessage(Component.text("Quantity must be between 1 and " + available + ".", NamedTextColor.RED));
 open();
 return;
 }
 plugin.getAuctionManager().purchaseUnits(ai, player, qty);
 open();
 } catch (NumberFormatException e) {
 player.sendMessage(Component.text("Invalid quantity.", NamedTextColor.RED));
 open();
 }
 });
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

package com.aureleconomy.auction;

import com.aureleconomy.AurelEconomy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.Base64;

public class AuctionManager {

 // Config & Default Constants
 private static final String DEFAULT_CURRENCY = "Aurels";
 private static final String CONF_TAX_PERCENT = "auction-house.sales-tax-percent";
 private static final BigDecimal DEFAULT_TAX = new BigDecimal("5.0");
 private static final long TICK_MINUTE = 1200L;

 // Message Constants
 private static final String MSG_OUTBID = "You have been outbid on %s! Your bid of %s was refunded.";
 private static final String MSG_CANCEL_OWN = "You can only cancel your own auctions.";
 private static final String MSG_CANCEL_BIDS = "You cannot cancel an auction that has bids!";
 private static final String MSG_CANCEL_SUCCESS = "Auction cancelled.";
 private static final String MSG_CANCEL_REFUND = "Auction cancelled. Refunded %s of listing fee.";
 private static final String MSG_INV_FULL = "Your inventory is full! Collect your item in /ah collect.";
 private static final String MSG_ITEM_RETURNED = "The item has been returned to your inventory.";
 private static final String MSG_OFFER_OWN = "You cannot make an offer on your own item!";
 private static final String MSG_OFFER_LIMIT = "Your offer must be lower than the current price (use bidding for higher amounts).";
 private static final String MSG_OFFER_SENT = "Offer of %s sent to the seller!";
 private static final String MSG_NEW_OFFER = "You received a new offer of %s for your %s! View it with /ah offers";
 private static final String MSG_OFFER_ACCEPTED = "Offer accepted! You earned %s";
 private static final String MSG_OFFER_ACCEPTED_BIDDER = "Your offer for %s was accepted! Money removed and item delivered.";
 private static final String MSG_NO_FUNDS = "The bidder no longer has enough funds.";
 private static final String MSG_PARTIAL_PURCHASE = "Purchased %d of %d %s for %s";
 private static final String MSG_FULL_PURCHASE = "Purchased full stack of %d %s for %s";
 private static final String MSG_SELLER_PARTIAL = "Sold %d of %d %s for %s (buyer: %s)";
 private static final String MSG_SELLER_FULL = "Sold full stack of %d %s for %s (buyer: %s)";
 private static final String MSG_INVALID_QTY = "Invalid quantity. Must be between 1 and %d.";
 private static final String MSG_INSUFFICIENT_ITEMS = "Only %d items remaining in this listing.";
 private static final String MSG_MUST_USE_UNIT = "This listing is in STACK mode. Use direct purchase.";

 private final AurelEconomy plugin;
 private final List<com.aureleconomy.auction.AuctionItem> activeAuctions = new ArrayList<>();

 public AuctionManager(AurelEconomy plugin) {
 this.plugin = plugin;
 loadAuctions();
 startExpiryTask();
 }

 private void loadAuctions() {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("SELECT id, seller_uuid, item_data, price, currency, is_bin, expiration, highest_bidder_uuid, ended, collected, listing_fee, start_time, purchase_mode FROM auctions WHERE ended = 0")) {
 ResultSet rs = ps.executeQuery();
 while (rs.next()) {
 activeAuctions.add(mapResultSet(rs));
 }
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Failed to load active auctions", e);
 }
 }

 private com.aureleconomy.auction.AuctionItem mapResultSet(ResultSet rs) throws SQLException {
 String currency = rs.getString("currency");
 if (currency == null)
 currency = DEFAULT_CURRENCY;

 BigDecimal price = rs.getBigDecimal("price");
 if (price == null)
 price = BigDecimal.ZERO;

 BigDecimal listingFee = rs.getBigDecimal("listing_fee");
 if (listingFee == null)
 listingFee = BigDecimal.ZERO;

 String bidderUuid = rs.getString("highest_bidder_uuid");

 String modeStr = rs.getString("purchase_mode");
 AuctionItem.PurchaseMode mode = AuctionItem.PurchaseMode.STACK;
 if ("UNIT".equalsIgnoreCase(modeStr)) {
 mode = AuctionItem.PurchaseMode.UNIT;
 }

 return new com.aureleconomy.auction.AuctionItem.Builder()
 .id(rs.getInt("id"))
 .seller(UUID.fromString(rs.getString("seller_uuid")))
 .item(itemFromBase64(rs.getString("item_data")))
 .price(price)
 .currency(currency)
 .isBin(rs.getBoolean("is_bin"))
 .expiration(rs.getLong("expiration"))
 .listingFee(listingFee)
 .startTime(rs.getLong("start_time"))
 .purchaseMode(mode)
 .highestBidder(bidderUuid != null ? UUID.fromString(bidderUuid) : null)
 .ended(rs.getBoolean("ended"))
 .collected(rs.getBoolean("collected"))
 .build();
 }

 public void listAuction(UUID seller, ItemStack item, BigDecimal price, String currency, boolean isBin,
 long durationMillis, BigDecimal listingFee, AuctionItem.PurchaseMode purchaseMode) {
 long now = System.currentTimeMillis();
 long expiration = now + durationMillis;

 final String cachedDisplayName = getItemDisplayName(item);

 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
 "INSERT INTO auctions (seller_uuid, item_data, price, currency, is_bin, expiration, listing_fee, start_time, purchase_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
 java.sql.Statement.RETURN_GENERATED_KEYS)) {
 ps.setString(1, seller.toString());
 ps.setString(2, itemToBase64(item));
 ps.setBigDecimal(3, price);
 ps.setString(4, currency);
 ps.setBoolean(5, isBin);
 ps.setLong(6, expiration);
 ps.setBigDecimal(7, listingFee);
 ps.setLong(8, now);
 ps.setString(9, purchaseMode.name());
 ps.executeUpdate();

 ResultSet rs = ps.getGeneratedKeys();
 if (rs.next()) {
 int id = rs.getInt(1);
 com.aureleconomy.auction.AuctionItem ai = new com.aureleconomy.auction.AuctionItem.Builder()
 .id(id).seller(seller).item(item).price(price).currency(currency)
 .isBin(isBin).expiration(expiration).listingFee(listingFee)
 .startTime(now).purchaseMode(purchaseMode).build();

 synchronized (activeAuctions) {
 activeAuctions.add(ai);
 }
 com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
 }
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error while listing auction", e);
 }
 });
 }

 public void bid(com.aureleconomy.auction.AuctionItem auction, UUID bidder, BigDecimal amount) {
 String currency = auction.getCurrency();

 final String cachedDisplayName = getItemDisplayName(auction.getItem());

 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET highest_bidder_uuid = ?, price = ? WHERE id = ? AND (price < ? OR highest_bidder_uuid IS NULL)")) {
 ps.setString(1, bidder.toString());
 ps.setBigDecimal(2, amount);
 ps.setInt(3, auction.getId());
 ps.setBigDecimal(4, amount);

 int affectedRows = ps.executeUpdate();
 if (affectedRows > 0) {
 synchronized (auction) {
 UUID previousBidder = auction.getHighestBidder();
 BigDecimal previousPrice = auction.getPrice();

 auction.setPrice(amount);
 auction.setHighestBidder(bidder);

 if (previousBidder != null) {
 plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(previousBidder), previousPrice, currency);
 Player prev = Bukkit.getPlayer(previousBidder);
 if (prev != null) {
 String formatted = plugin.getEconomyManager().getFormattedWithSymbol(previousPrice, currency);
 prev.sendMessage(Component.text(String.format(MSG_OUTBID, cachedDisplayName, formatted), NamedTextColor.YELLOW));
 }
 }
 }
 com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
 } else {
 Player p = Bukkit.getPlayer(bidder);
 if (p != null) {
 p.sendMessage(Component.text("Your bid was too late! Someone else already bid higher.", NamedTextColor.RED));
 }
 }
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error during bidding", e);
 }
 });
 }

 /**
 * Purchase a specific quantity of items from a UNIT-mode listing.
 * Returns the amount actually purchased (0 on failure).
 */
 public int purchaseUnits(com.aureleconomy.auction.AuctionItem auction, Player buyer, int quantity) {
 if (auction.getPurchaseMode() != AuctionItem.PurchaseMode.UNIT) {
 buyer.sendMessage(Component.text(MSG_MUST_USE_UNIT, NamedTextColor.RED));
 return 0;
 }

 int available = auction.getAvailableQuantity();
 if (available <= 0) {
 buyer.sendMessage(Component.text("This listing has no items remaining.", NamedTextColor.RED));
 return 0;
 }

 quantity = Math.min(quantity, available);
 if (quantity <= 0) {
 buyer.sendMessage(Component.text("Invalid quantity.", NamedTextColor.RED));
 return 0;
 }

 BigDecimal unitPrice = auction.getPricePerUnit();
 BigDecimal totalCost = unitPrice.multiply(BigDecimal.valueOf(quantity))
 .setScale(2, RoundingMode.HALF_UP);
 String currency = auction.getCurrency();

 if (!plugin.getEconomyManager().has(buyer, totalCost, currency)) {
 buyer.sendMessage(Component.text("You don't have enough %currency!".replace("%currency%", currency), NamedTextColor.RED));
 return 0;
 }

 final int finalQuantity = quantity;
 final BigDecimal finalTotalCost = totalCost;
 final int remaining = available - quantity;

 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try {
 synchronized (auction) {
 if (auction.isEnded()) {
 buyer.sendMessage(Component.text("This auction has ended.", NamedTextColor.RED));
 return;
 }
 if (auction.getAvailableQuantity() < finalQuantity) {
 buyer.sendMessage(Component.text(MSG_INSUFFICIENT_ITEMS.replace("%d", String.valueOf(auction.getAvailableQuantity())), NamedTextColor.RED));
 return;
 }

 plugin.getEconomyManager().withdraw(buyer, finalTotalCost, currency);

 ItemStack purchased = auction.getItem().clone();
 purchased.setAmount(finalQuantity);

 if (com.aureleconomy.utils.InventoryUtils.hasSpace(buyer.getInventory(), purchased, finalQuantity)) {
 buyer.getInventory().addItem(purchased);
 } else {
 // Give item via /ah collect
 plugin.getAuctionManager().sendToCollectionBin(buyer.getUniqueId(), purchased);
 buyer.sendMessage(Component.text("Your inventory was full! The item has been sent to /ah collect.", NamedTextColor.YELLOW));
 }

 // Reduce the auction item stack
 auction.getItem().setAmount(remaining);

 // Pay seller (minus tax)
 BigDecimal taxRate = BigDecimal.valueOf(plugin.getConfig().getDouble(CONF_TAX_PERCENT, DEFAULT_TAX.doubleValue()))
 .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
 BigDecimal tax = finalTotalCost.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
 BigDecimal sellerPayout = finalTotalCost.subtract(tax).setScale(2, RoundingMode.HALF_UP);

 Player seller = Bukkit.getPlayer(auction.getSeller());
 plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(auction.getSeller()), sellerPayout, currency);

 String buyerMsg = String.format(remaining > 0 ? MSG_PARTIAL_PURCHASE : MSG_FULL_PURCHASE,
 finalQuantity, available + finalQuantity,
 getItemDisplayName(auction.getItem()),
 plugin.getEconomyManager().getFormattedWithSymbol(finalTotalCost, currency));
 buyer.sendMessage(Component.text(buyerMsg, NamedTextColor.GREEN));

 String sellerMsg = String.format(remaining > 0 ? MSG_SELLER_PARTIAL : MSG_SELLER_FULL,
 finalQuantity, available + finalQuantity,
 getItemDisplayName(auction.getItem()),
 plugin.getEconomyManager().getFormattedWithSymbol(sellerPayout, currency),
 buyer.getName());
 if (seller != null) {
 seller.sendMessage(Component.text(sellerMsg, NamedTextColor.GREEN));
 }

 // Update DB with remaining quantity
 plugin.getDatabaseManager().getWriteLock();
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET item_data = ?, price = ? WHERE id = ?")) {
 ItemStack updatedStack = auction.getItem().clone();
 updatedStack.setAmount(remaining);
 ps.setString(1, itemToBase64(updatedStack));
 ps.setBigDecimal(2, remaining > 0 ? unitPrice : BigDecimal.ZERO);
 ps.setInt(3, auction.getId());
 ps.executeUpdate();
 } finally {
 plugin.getDatabaseManager().getWriteLock().notifyAll();
 }

 if (remaining <= 0) {
 endAuction(auction);
 } else {
 com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
 }
 }
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error during unit purchase", e);
 buyer.sendMessage(Component.text("Purchase failed. Please try again.", NamedTextColor.RED));
 }
 });
 return finalQuantity;
 }

 public void cancelAuction(com.aureleconomy.auction.AuctionItem ai, Player player) {
 if (!ai.getSeller().equals(player.getUniqueId())) {
 player.sendMessage(Component.text(MSG_CANCEL_OWN, NamedTextColor.RED));
 return;
 }

 if (ai.getHighestBidder() != null) {
 player.sendMessage(Component.text(MSG_CANCEL_BIDS, NamedTextColor.RED));
 return;
 }

 ai.setEnded(true);
 synchronized (activeAuctions) {
 activeAuctions.remove(ai);
 }

 long totalDuration = ai.getExpiration() - ai.getStartTime();
 long remainingTime = ai.getExpiration() - System.currentTimeMillis();
 BigDecimal refund = BigDecimal.ZERO;
 if (totalDuration > 0 && remainingTime > 0) {
 BigDecimal ratio = BigDecimal.valueOf(remainingTime).divide(BigDecimal.valueOf(totalDuration), 4,
 RoundingMode.HALF_UP);
 refund = ai.getListingFee().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
 }

 final BigDecimal finalRefund = refund;

 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET ended = 1 WHERE id = ? AND ended = 0")) {
 ps.setInt(1, ai.getId());
 int rows = ps.executeUpdate();

 if (rows == 0) return;

 Bukkit.getScheduler().runTask(plugin, (Runnable) () -> {
 if (finalRefund.compareTo(BigDecimal.ZERO) > 0) {
 plugin.getEconomyManager().deposit(player, finalRefund, ai.getCurrency());
 String formatted = plugin.getEconomyManager().getFormattedWithSymbol(finalRefund,
 ai.getCurrency());
 player.sendMessage(
 Component.text(String.format(MSG_CANCEL_REFUND, formatted), NamedTextColor.GREEN));
 } else {
 player.sendMessage(Component.text(MSG_CANCEL_SUCCESS, NamedTextColor.GREEN));
 }

 if (com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), ai.getItem(),
 ai.getItem().getAmount())) {
 player.getInventory().addItem(ai.getItem());
 markCollected(ai.getId());
 player.sendMessage(Component.text(MSG_ITEM_RETURNED, NamedTextColor.GREEN));
 } else {
 player.sendMessage(Component.text(MSG_INV_FULL, NamedTextColor.YELLOW));
 }
 com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
 });
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error during cancellation", e);
 }
 });
 }

 public void endAuction(com.aureleconomy.auction.AuctionItem auction) {
 auction.setEnded(true);
 synchronized (activeAuctions) {
 activeAuctions.remove(auction);
 }

 final String cachedDisplayName = getItemDisplayName(auction.getItem());

 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET ended = 1 WHERE id = ?")) {
 ps.setInt(1, auction.getId());
 ps.executeUpdate();
 com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Failed to end auction in database", e);
 }
 });

 Player seller = Bukkit.getPlayer(auction.getSeller());
 double taxRate = plugin.getConfig().getDouble(CONF_TAX_PERCENT, DEFAULT_TAX.doubleValue());
 BigDecimal taxMultiplier = BigDecimal.ONE
 .subtract(BigDecimal.valueOf(taxRate).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));

 if (auction.getHighestBidder() != null) {
 BigDecimal finalPrice = auction.getPrice().multiply(taxMultiplier).setScale(2, RoundingMode.HALF_UP);
 plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(auction.getSeller()), finalPrice,
 auction.getCurrency());

 if (seller != null) {
 seller.sendMessage(Component.text(
 "Your auction sold for "
 + plugin.getEconomyManager().getFormattedWithSymbol(finalPrice, auction.getCurrency()),
 NamedTextColor.GREEN));
 } else {
 logOfflineEarning(auction.getSeller(), finalPrice, auction.getItem(), cachedDisplayName);
 }
 } else if (seller != null) {
 seller.sendMessage(Component.text("Your auction expired without bids. Collect items in /ah collect.",
 NamedTextColor.YELLOW));
 }
 }

 private void logOfflineEarning(UUID uuid, BigDecimal amount, ItemStack item, String displayName) {
 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
 "INSERT INTO offline_earnings (uuid, amount, item_display, timestamp) VALUES (?, ?, ?, ?)")) {
 ps.setString(1, uuid.toString());
 ps.setBigDecimal(2, amount);

 String display = displayName + " (x" + item.getAmount() + ")";

 ps.setString(3, display);
 ps.setLong(4, System.currentTimeMillis());
 ps.executeUpdate();
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error logging offline earning", e);
 }
 });
 }

 private void startExpiryTask() {
 Bukkit.getScheduler().runTaskTimer(plugin, () -> {
 long now = System.currentTimeMillis();
 List<com.aureleconomy.auction.AuctionItem> toEnd = new ArrayList<>();
 synchronized (activeAuctions) {
 for (com.aureleconomy.auction.AuctionItem ai : activeAuctions) {
 if (ai.getExpiration() <= now) {
 toEnd.add(ai);
 }
 }
 }
 for (com.aureleconomy.auction.AuctionItem ai : toEnd)
 endAuction(ai);
 }, TICK_MINUTE, TICK_MINUTE);
 }

 public List<com.aureleconomy.auction.AuctionItem> getActiveAuctions() {
 synchronized (activeAuctions) {
 return new ArrayList<>(activeAuctions);
 }
 }

 public com.aureleconomy.auction.AuctionItem getAuctionById(int id) {
 synchronized (activeAuctions) {
 return activeAuctions.stream().filter(ai -> ai.getId() == id).findFirst().orElse(null);
 }
 }

 public List<com.aureleconomy.auction.AuctionItem> getCollectionBin(UUID playerUUID) {
 List<com.aureleconomy.auction.AuctionItem> items = new ArrayList<>();
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
 "SELECT id, seller_uuid, item_data, price, currency, is_bin, expiration, highest_bidder_uuid, ended, collected, listing_fee, start_time, purchase_mode FROM auctions WHERE collected = 0 AND ended = 1 AND ((seller_uuid = ? AND highest_bidder_uuid IS NULL) OR (highest_bidder_uuid = ?))")) {
 ps.setString(1, playerUUID.toString());
 ps.setString(2, playerUUID.toString());
 ResultSet rs = ps.executeQuery();
 while (rs.next()) {
 items.add(mapResultSet(rs));
 }
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error fetching collection bin", e);
 }
 return items;
 }

 public void getOffersForSeller(UUID seller, Consumer<List<Offer>> callback) {
 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 List<Offer> offers = new ArrayList<>();
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
 "SELECT o.id, o.auction_id, o.bidder_uuid, o.amount, o.currency, o.status, o.timestamp FROM auction_offers o JOIN auctions a ON o.auction_id = a.id WHERE a.seller_uuid = ? AND o.status = 'PENDING'")) {
 ps.setString(1, seller.toString());
 ResultSet rs = ps.executeQuery();
 while (rs.next()) {
 offers.add(new Offer(
 rs.getInt("id"),
 rs.getInt("auction_id"),
 UUID.fromString(rs.getString("bidder_uuid")),
 rs.getBigDecimal("amount"),
 OfferStatus.valueOf(rs.getString("status")),
 rs.getLong("timestamp")));
 }
 callback.accept(offers);
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error fetching offers", e);
 }
 });
 }

 public void makeOffer(com.aureleconomy.auction.AuctionItem ai, Player bidder, BigDecimal amount) {
 if (ai.getSeller().equals(bidder.getUniqueId())) {
 bidder.sendMessage(Component.text(MSG_OFFER_OWN, NamedTextColor.RED));
 return;
 }

 if (amount.compareTo(ai.getPrice()) >= 0) {
 bidder.sendMessage(Component.text(MSG_OFFER_LIMIT, NamedTextColor.RED));
 return;
 }

 final String cachedDisplayName = getItemDisplayName(ai.getItem());

 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
 "INSERT INTO auction_offers (auction_id, bidder_uuid, amount, status, timestamp) VALUES (?, ?, ?, ?, ?)")) {
 ps.setInt(1, ai.getId());
 ps.setString(2, bidder.getUniqueId().toString());
 ps.setBigDecimal(3, amount);
 ps.setString(4, OfferStatus.PENDING.name());
 ps.setLong(5, System.currentTimeMillis());
 ps.executeUpdate();

 bidder.sendMessage(Component.text(String.format(MSG_OFFER_SENT, amount), NamedTextColor.GREEN));

 Player seller = Bukkit.getPlayer(ai.getSeller());
 if (seller != null) {
 seller.sendMessage(Component.text(
 String.format(MSG_NEW_OFFER, amount, cachedDisplayName), NamedTextColor.GOLD));
 }
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error making offer", e);
 }
 });
 }

 public void acceptOffer(int offerId, Player seller) {
 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try {
 Offer offer = null;
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("SELECT id, auction_id, bidder_uuid, amount, currency, status, timestamp FROM auction_offers WHERE id = ?")) {
 ps.setInt(1, offerId);
 ResultSet rs = ps.executeQuery();
 if (rs.next()) {
 offer = new Offer(rs.getInt("id"), rs.getInt("auction_id"),
 UUID.fromString(rs.getString("bidder_uuid")), rs.getBigDecimal("amount"),
 OfferStatus.valueOf(rs.getString("status")), rs.getLong("timestamp"));
 }
 }

 if (offer == null)
 return;
 com.aureleconomy.auction.AuctionItem ai = getAuctionById(offer.getAuctionId());
 if (ai == null)
 return;

 final String cachedDisplayName = getItemDisplayName(ai.getItem());

 Offer finalOffer = offer;
 Bukkit.getScheduler().runTask(plugin, (Runnable) () -> {
 if (updateOfferStatusAtomic(offerId, OfferStatus.ACCEPTED)) {
 if (plugin.getEconomyManager().has(Bukkit.getOfflinePlayer(finalOffer.getBidder()),
 finalOffer.getAmount(), ai.getCurrency())) {
 plugin.getEconomyManager().withdraw(Bukkit.getOfflinePlayer(finalOffer.getBidder()),
 finalOffer.getAmount(), ai.getCurrency());
 plugin.getEconomyManager().deposit(seller, finalOffer.getAmount(), ai.getCurrency());

 Player bidder = Bukkit.getPlayer(finalOffer.getBidder());
 if (bidder != null) {
 if (com.aureleconomy.utils.InventoryUtils.hasSpace(bidder.getInventory(), ai.getItem(),
 ai.getItem().getAmount())) {
 bidder.getInventory().addItem(ai.getItem().clone());
 markCollected(ai.getId());
 bidder.sendMessage(Component.text(
 String.format(MSG_OFFER_ACCEPTED_BIDDER, cachedDisplayName),
 NamedTextColor.GREEN));
 } else {
 bidder.sendMessage(Component.text(
 "Your inventory was full! The item has been sent to /ah collect.",
 NamedTextColor.YELLOW));
 }
 }

 endAuction(ai);
 seller.sendMessage(Component.text(String.format(MSG_OFFER_ACCEPTED, finalOffer.getAmount()),
 NamedTextColor.GREEN));
 } else {
 seller.sendMessage(Component.text(MSG_NO_FUNDS, NamedTextColor.RED));
 updateOfferStatus(offerId, OfferStatus.EXPIRED);
 }
 }
 });
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Error accepting offer", e);
 }
 });
 }

 public void declineOffer(int offerId, Player seller) {
 updateOfferStatus(offerId, OfferStatus.REJECTED);
 seller.sendMessage(Component.text("Offer declined.", NamedTextColor.YELLOW));
 }

 public boolean claimAuctionAtomic(int id) {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET ended = 1 WHERE id = ? AND ended = 0")) {
 ps.setInt(1, id);
 return ps.executeUpdate() > 0;
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error claiming auction", e);
 return false;
 }
 }

 public boolean updateOfferStatusAtomic(int offerId, OfferStatus newStatus) {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auction_offers SET status = ? WHERE id = ? AND status = 'PENDING'")) {
 ps.setString(1, newStatus.name());
 ps.setInt(2, offerId);
 return ps.executeUpdate() > 0;
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error updating offer status", e);
 return false;
 }
 }

 public void updateOfferStatus(int offerId, OfferStatus status) {
 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auction_offers SET status = ? WHERE id = ?")) {
 ps.setString(1, status.name());
 ps.setInt(2, offerId);
 ps.executeUpdate();
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error updating offer status", e);
 }
 });
 }

 public boolean markCollectedAtomic(int id) {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET collected = 1 WHERE id = ? AND collected = 0")) {
 ps.setInt(1, id);
 int rowsUpdated = ps.executeUpdate();
 return rowsUpdated > 0;
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error marking collected", e);
 return false;
 }
 }

 public void markCollected(int id) {
 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
 .prepareStatement("UPDATE auctions SET collected = 1 WHERE id = ? AND collected = 0")) {
 ps.setInt(1, id);
 ps.executeUpdate();
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error marking collected", e);
 }
 });
 }

 public void sendToCollectionBin(UUID playerUUID, ItemStack item) {
 Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
 try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
 "INSERT INTO auctions (seller_uuid, item_data, price, currency, is_bin, expiration, listing_fee, start_time, ended, collected) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
 ps.setString(1, playerUUID.toString());
 ps.setString(2, itemToBase64(item));
 ps.setBigDecimal(3, BigDecimal.ZERO);
 ps.setString(4, DEFAULT_CURRENCY);
 ps.setBoolean(5, true);
 ps.setLong(6, System.currentTimeMillis());
 ps.setBigDecimal(7, BigDecimal.ZERO);
 ps.setLong(8, System.currentTimeMillis());
 ps.setBoolean(9, true);
 ps.setBoolean(10, false);
 ps.executeUpdate();
 com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
 } catch (SQLException e) {
 plugin.getComponentLogger().error("Database error sending item to collection bin", e);
 }
 });
 }

 String getItemDisplayName(ItemStack item) {
 if (Bukkit.isPrimaryThread()) {
 com.aureleconomy.scanner.CustomItemRegistry registry = plugin.getCustomItemRegistry();
 if (registry != null) {
 java.util.Optional<String> customId = registry.resolveItemId(item);
 if (customId.isPresent()) {
 com.aureleconomy.scanner.CustomMarketItem customItem = registry.getById(customId.get());
 if (customItem != null && customItem.getDisplayName() != null && !customItem.getDisplayName().isEmpty()) {
 return customItem.getDisplayName();
 }
 }
 }
 if (item.hasItemMeta()) {
 ItemMeta meta = item.getItemMeta();
 if (meta.hasDisplayName() || meta.displayName() != null) {
 net.kyori.adventure.text.Component display = meta.displayName();
 if (display != null) {
 return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
 .serialize(display);
 }
 }
 }
 }
 // Fallback for async context: avoid ItemMeta reads
 // Format MATERIAL_NAME as "Material Name" for display
 String typeName = item.getType().name();
 StringBuilder formatted = new StringBuilder();
 for (String part : typeName.split("_")) {
 if (!formatted.isEmpty()) formatted.append(" ");
 formatted.append(part.charAt(0)).append(part.substring(1).toLowerCase());
 }
 return formatted.toString();
 }

 private String itemToBase64(ItemStack item) {
 return Base64.getEncoder().encodeToString(item.serializeAsBytes());
 }

 private ItemStack itemFromBase64(String data) {
 return ItemStack.deserializeBytes(Base64.getDecoder().decode(data));
 }
}

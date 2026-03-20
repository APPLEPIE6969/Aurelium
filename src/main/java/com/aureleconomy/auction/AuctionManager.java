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
import org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder;

/**
 * Manages the Auction House logic, including listings, bids, and offers.
 * Refactored for Senior Java standards: BigDecimal for currency, Builder
 * pattern,
 * Enums for state, and elimination of magic variables.
 */
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

    private final AurelEconomy plugin;
    private final List<com.aureleconomy.auction.AuctionItem> activeAuctions = new ArrayList<>();

    public AuctionManager(AurelEconomy plugin) {
        this.plugin = plugin;
        loadAuctions();
        startExpiryTask();
    }

    private void loadAuctions() {
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                .prepareStatement("SELECT * FROM auctions WHERE ended = 0")) {
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
                .highestBidder(bidderUuid != null ? UUID.fromString(bidderUuid) : null)
                .ended(rs.getBoolean("ended"))
                .collected(rs.getBoolean("collected"))
                .build();
    }

    public void listAuction(UUID seller, ItemStack item, BigDecimal price, String currency, boolean isBin,
            long durationMillis, BigDecimal listingFee) {
        long now = System.currentTimeMillis();
        long expiration = now + durationMillis;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO auctions (seller_uuid, item_data, price, currency, is_bin, expiration, listing_fee, start_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, seller.toString());
                ps.setString(2, itemToBase64(item));
                ps.setBigDecimal(3, price);
                ps.setString(4, currency);
                ps.setBoolean(5, isBin);
                ps.setLong(6, expiration);
                ps.setBigDecimal(7, listingFee);
                ps.setLong(8, now);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int id = rs.getInt(1);
                    com.aureleconomy.auction.AuctionItem ai = new com.aureleconomy.auction.AuctionItem.Builder()
                            .id(id).seller(seller).item(item).price(price).currency(currency)
                            .isBin(isBin).expiration(expiration).listingFee(listingFee)
                            .startTime(now).build();

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

        // Perform atomic update in DB first to ensure we actually won the bid slot
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                    .prepareStatement("UPDATE auctions SET highest_bidder_uuid = ?, price = ? WHERE id = ? AND (price < ? OR highest_bidder_uuid IS NULL)")) {
                ps.setString(1, bidder.toString());
                ps.setBigDecimal(2, amount);
                ps.setInt(3, auction.getId());
                ps.setBigDecimal(4, amount);
                
                int affectedRows = ps.executeUpdate();
                if (affectedRows > 0) {
                    // We successfully placed the bid
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
                                prev.sendMessage(Component.text(String.format(MSG_OUTBID, auction.getItem().getType().name(), formatted), NamedTextColor.YELLOW));
                            }
                        }
                    }
                    com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
                } else {
                    // Bid failed (someone else bid higher already or same amount)
                    Player p = Bukkit.getPlayer(bidder);
                    if (p != null) {
                        p.sendMessage(Component.text("Your bid was too late! Someone else already bid higher.", NamedTextColor.RED));
                        // Refund immediately if the money was already taken (GUI usually handles this, 
                        // but if called from web, we need to be careful)
                    }
                    // For web purchases, failure will be caught by the executePurchases logic
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error during bidding", e);
            }
        });
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
                
                if (rows == 0) return; // Already ended

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
                logOfflineEarning(auction.getSeller(), finalPrice, auction.getItem());
            }
        } else if (seller != null) {
            seller.sendMessage(Component.text("Your auction expired without bids. Collect items in /ah collect.",
                    NamedTextColor.YELLOW));
        }
    }

    private void logOfflineEarning(UUID uuid, BigDecimal amount, ItemStack item) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO offline_earnings (uuid, amount, item_display, timestamp) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setBigDecimal(2, amount);

                String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName()).content()
                        : item.getType().name();
                String display = itemName + " (x" + item.getAmount() + ")";

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
                "SELECT * FROM auctions WHERE collected = 0 AND ended = 1 AND ((seller_uuid = ? AND highest_bidder_uuid IS NULL) OR (highest_bidder_uuid = ?))")) {
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
                    "SELECT o.* FROM auction_offers o JOIN auctions a ON o.auction_id = a.id WHERE a.seller_uuid = ? AND o.status = 'PENDING'")) {
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
                            String.format(MSG_NEW_OFFER, amount, ai.getItem().getType().name()), NamedTextColor.GOLD));
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
                        .prepareStatement("SELECT * FROM auction_offers WHERE id = ?")) {
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

                Offer finalOffer = offer;
                Bukkit.getScheduler().runTask(plugin, (Runnable) () -> {
                    // Atomic status update to PREVENT double-acceptance
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
                                            String.format(MSG_OFFER_ACCEPTED_BIDDER, ai.getItem().getType().name()),
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

    /**
     * Atomically claims an auction for purchase. Returns true if successful.
     */
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

    /**
     * Atomically marks an auction as collected. Returns true if the update was
     * successful
     * (item was not already collected), false if already collected or on error.
     * This prevents item duplication from rapid clicking.
     */
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
                ps.setBoolean(9, true); // Ended
                ps.setBoolean(10, false); // Not collected
                ps.executeUpdate();
                com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error sending item to collection bin", e);
            }
        });
    }

    private String itemToBase64(ItemStack item) {
        return Base64Coder.encodeLines(item.serializeAsBytes());
    }

    private ItemStack itemFromBase64(String data) {
        return ItemStack.deserializeBytes(Base64Coder.decodeLines(data));
    }
}

package com.aureleconomy.auction;

import com.aureleconomy.AurelEconomy;
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

public class AuctionManager {

    private final AurelEconomy plugin;
    private final List<AuctionItem> activeAuctions = new ArrayList<>();

    public AuctionManager(AurelEconomy plugin) {
        this.plugin = plugin;
        loadAuctions();
        startExpiryTask();
    }

    private void loadAuctions() {
        // Load active auctions from DB
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                .prepareStatement("SELECT * FROM auctions WHERE ended = 0")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                activeAuctions.add(mapResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Ensure to load collected items on demand or cache them differently?
        // For simplicity, we might just query DB for collection bin when player runs
        // /ah collect
    }

    private AuctionItem mapResultSet(ResultSet rs) throws SQLException {
        return new AuctionItem(
                rs.getInt("id"),
                UUID.fromString(rs.getString("seller_uuid")),
                itemFromBase64(rs.getString("item_data")),
                rs.getDouble("price"),
                rs.getBoolean("is_bin"),
                rs.getLong("expiration"),
                rs.getDouble("listing_fee"),
                rs.getLong("start_time"),
                rs.getString("highest_bidder_uuid") != null ? UUID.fromString(rs.getString("highest_bidder_uuid"))
                        : null,
                rs.getBoolean("ended"),
                rs.getBoolean("collected"));
    }

    public void listAuction(UUID seller, ItemStack item, double price, boolean isBin, long durationMillis,
            double listingFee) {
        long now = System.currentTimeMillis();
        long expiration = now + durationMillis;

        // Save to DB
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO auctions (seller_uuid, item_data, price, is_bin, expiration, listing_fee, start_time) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, seller.toString());
                ps.setString(2, itemToBase64(item));
                ps.setDouble(3, price);
                ps.setBoolean(4, isBin);
                ps.setLong(5, expiration);
                ps.setDouble(6, listingFee);
                ps.setLong(7, now);
                ps.executeUpdate();

                ResultSet rs = ps.getGeneratedKeys();
                if (rs.next()) {
                    int id = rs.getInt(1);
                    AuctionItem ai = new AuctionItem(id, seller, item, price, isBin, expiration, listingFee, now, null,
                            false, false);
                    synchronized (activeAuctions) {
                        activeAuctions.add(ai);
                    }
                    com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void bid(AuctionItem auction, UUID bidder, double amount) {
        UUID previousBidder = auction.getHighestBidder();
        double previousPrice = auction.getPrice();

        auction.setPrice(amount);
        auction.setHighestBidder(bidder);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                    .prepareStatement("UPDATE auctions SET price = ?, highest_bidder_uuid = ? WHERE id = ?")) {
                ps.setDouble(1, amount);
                ps.setString(2, bidder.toString());
                ps.setInt(3, auction.getId());
                ps.executeUpdate();

                // Refund previous bidder
                if (previousBidder != null) {
                    plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(previousBidder), previousPrice);
                    Player prev = Bukkit.getPlayer(previousBidder);
                    if (prev != null) {
                        prev.sendMessage(Component.text("You have been outbid on " + auction.getItem().getType().name()
                                + "! Your bid of " + previousPrice + " was refunded.", NamedTextColor.YELLOW));
                    }
                }

                com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void cancelAuction(AuctionItem ai, Player player) {
        if (!ai.getSeller().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You can only cancel your own auctions.", NamedTextColor.RED));
            return;
        }

        if (ai.getHighestBidder() != null) {
            player.sendMessage(Component.text("You cannot cancel an auction that has bids!", NamedTextColor.RED));
            return;
        }

        ai.setEnded(true);
        synchronized (activeAuctions) {
            activeAuctions.remove(ai);
        }

        // Calculate refund
        long totalDuration = ai.getExpiration() - ai.getStartTime();
        long remainingTime = ai.getExpiration() - System.currentTimeMillis();
        double refund = 0;
        if (totalDuration > 0 && remainingTime > 0) {
            double ratio = (double) remainingTime / totalDuration;
            refund = ai.getListingFee() * ratio;
        }

        double finalRefund = refund;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                    .prepareStatement("UPDATE auctions SET ended = 1 WHERE id = ?")) {
                ps.setInt(1, ai.getId());
                ps.executeUpdate();

                // Give refund and item
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (finalRefund > 0) {
                        plugin.getEconomyManager().deposit(player, finalRefund);
                        player.sendMessage(Component.text("Auction cancelled. Refunded "
                                + plugin.getEconomyManager().format(finalRefund) + " of listing fee.",
                                NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Auction cancelled.", NamedTextColor.GREEN));
                    }

                    if (player.getInventory().firstEmpty() != -1) {
                        player.getInventory().addItem(ai.getItem());
                        markCollected(ai.getId());
                        player.sendMessage(
                                Component.text("The item has been returned to your inventory.", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Your inventory is full! Collect your item in /ah collect.",
                                NamedTextColor.YELLOW));
                    }
                    com.aureleconomy.gui.AuctionGUI.refreshAllViewers();
                });
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void endAuction(AuctionItem auction) {
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
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });

        // Notify seller/winner if online
        Player seller = Bukkit.getPlayer(auction.getSeller());
        if (seller != null) {
            if (auction.getHighestBidder() != null) {
                // Sold
                double taxPercent = plugin.getConfig().getDouble("auction-house.sales-tax-percent", 5.0);
                double finalPrice = auction.getPrice() * (1 - (taxPercent / 100.0));
                plugin.getEconomyManager().deposit(seller, finalPrice);
                seller.sendMessage("Your auction sold for " + finalPrice); // use messages.yml in real impl
            } else {
                // Nobody bought it, goes to collection bin
                seller.sendMessage(Component.text("Your auction expired without bids. Collect items in /ah collect.",
                        NamedTextColor.YELLOW));
            }
        } else {
            if (auction.getHighestBidder() != null) {
                // Offline seller, deposit money directly
                double taxPercent = plugin.getConfig().getDouble("auction-house.sales-tax-percent", 5.0);
                double finalPrice = auction.getPrice() * (1 - (taxPercent / 100.0));
                plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(auction.getSeller()), finalPrice);

                // Log notification
                logOfflineEarning(auction.getSeller(), finalPrice, auction.getItem());
            }
        }
    }

    private void logOfflineEarning(UUID uuid, double amount, ItemStack item) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO offline_earnings (uuid, amount, item_display, timestamp) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setDouble(2, amount);

                String itemName = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        ? ((net.kyori.adventure.text.TextComponent) item.getItemMeta().displayName()).content()
                        : item.getType().name();
                // Simple display "Diamond Sword (x1)"
                String display = itemName + " (x" + item.getAmount() + ")";

                ps.setString(3, display);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    private void startExpiryTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.currentTimeMillis();
            List<AuctionItem> toEnd = new ArrayList<>();
            synchronized (activeAuctions) {
                for (AuctionItem ai : activeAuctions) {
                    if (ai.getExpiration() <= now) {
                        toEnd.add(ai);
                    }
                }
            }
            for (AuctionItem ai : toEnd)
                endAuction(ai);
        }, 1200L, 1200L); // check every minute
    }

    public List<AuctionItem> getActiveAuctions() {
        synchronized (activeAuctions) {
            return new ArrayList<>(activeAuctions);
        }
    }

    public AuctionItem getAuctionById(int id) {
        synchronized (activeAuctions) {
            return activeAuctions.stream().filter(ai -> ai.getId() == id).findFirst().orElse(null);
        }
    }

    public List<AuctionItem> getCollectionBin(UUID playerUUID) {
        List<AuctionItem> items = new ArrayList<>();
        // Query DB for ended auctions where (seller = uuid AND no bids) OR
        // (highest_bidder = uuid) AND collected = 0
        // This logic is slightly complex:
        // 1. If I sold it, I get money (handled in endAuction usually), but if items
        // expired I get item back.
        // 2. If I won it, I get item.
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                "SELECT * FROM auctions WHERE collected = 0 AND ended = 1 AND ((seller_uuid = ? AND highest_bidder_uuid IS NULL) OR (highest_bidder_uuid = ?))")) {
            ps.setString(1, playerUUID.toString());
            ps.setString(2, playerUUID.toString());
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                items.add(mapResultSet(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return items;
    }

    public void sendToCollectionBin(UUID bidder, ItemStack item) {
        // We reuse the auctions database for collection bin storage to avoid building a
        // new table.
        // We create an "ended" auction where seller is null and highest_bidder is the
        // target player
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO auctions (highest_bidder_uuid, item_data, ended, collected) VALUES (?, ?, 1, 0)")) {
                ps.setString(1, bidder.toString());
                ps.setString(2, itemToBase64(item));
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
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
                            rs.getDouble("amount"),
                            rs.getString("status"),
                            rs.getLong("timestamp")));
                }
                callback.accept(offers);
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void makeOffer(AuctionItem ai, Player bidder, double amount) {
        if (ai.getSeller().equals(bidder.getUniqueId())) {
            bidder.sendMessage(Component.text("You cannot make an offer on your own item!", NamedTextColor.RED));
            return;
        }

        if (amount >= ai.getPrice()) {
            bidder.sendMessage(
                    Component.text("Your offer must be lower than the current price (use bidding for higher amounts).",
                            NamedTextColor.RED));
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO auction_offers (auction_id, bidder_uuid, amount, status, timestamp) VALUES (?, ?, ?, ?, ?)")) {
                ps.setInt(1, ai.getId());
                ps.setString(2, bidder.getUniqueId().toString());
                ps.setDouble(3, amount);
                ps.setString(4, "PENDING");
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
                bidder.sendMessage(Component.text("Offer of " + amount + " sent to the seller!", NamedTextColor.GREEN));

                Player seller = Bukkit.getPlayer(ai.getSeller());
                if (seller != null) {
                    seller.sendMessage(Component.text("You received a new offer of " + amount + " for your "
                            + ai.getItem().getType().name() + "! View it with /ah offers", NamedTextColor.GOLD));
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void acceptOffer(int offerId, Player seller) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Get offer details
                Offer offer = null;
                try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                        .prepareStatement("SELECT * FROM auction_offers WHERE id = ?")) {
                    ps.setInt(1, offerId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        offer = new Offer(rs.getInt("id"), rs.getInt("auction_id"),
                                UUID.fromString(rs.getString("bidder_uuid")), rs.getDouble("amount"),
                                rs.getString("status"), rs.getLong("timestamp"));
                    }
                }

                if (offer == null)
                    return;
                AuctionItem ai = getAuctionById(offer.getAuctionId());
                if (ai == null)
                    return;

                // Sync check funds of bidder
                Offer finalOffer = offer;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player bidder = Bukkit.getPlayer(finalOffer.getBidder());
                    if (plugin.getEconomyManager().has(Bukkit.getOfflinePlayer(finalOffer.getBidder()),
                            finalOffer.getAmount())) {
                        plugin.getEconomyManager().withdraw(Bukkit.getOfflinePlayer(finalOffer.getBidder()),
                                finalOffer.getAmount());
                        plugin.getEconomyManager().deposit(seller, finalOffer.getAmount());

                        // Mark offer as accepted
                        updateOfferStatus(offerId, "ACCEPTED");

                        // Hand over item (offline handling would be better but let's do immediate or
                        // collection)
                        if (bidder != null && com.aureleconomy.utils.InventoryUtils.hasSpace(bidder.getInventory(),
                                ai.getItem(), ai.getItem().getAmount())) {
                            bidder.getInventory().addItem(ai.getItem().clone());
                            markCollected(ai.getId()); // Item is gone
                            bidder.sendMessage(Component.text(
                                    "Your offer for " + ai.getItem().getType().name()
                                            + " was accepted! Money removed and item delivered.",
                                    NamedTextColor.GREEN));
                        } else {
                            // To collection bin
                            // Mark collection bin logic
                        }

                        endAuction(ai);
                        seller.sendMessage(Component.text("Offer accepted! You earned " + finalOffer.getAmount(),
                                NamedTextColor.GREEN));
                    } else {
                        seller.sendMessage(
                                Component.text("The bidder no longer has enough funds.", NamedTextColor.RED));
                        updateOfferStatus(offerId, "CANCELLED_NO_FUNDS");
                    }
                });
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void declineOffer(int offerId, Player seller) {
        updateOfferStatus(offerId, "DECLINED");
        seller.sendMessage(Component.text("Offer declined.", NamedTextColor.YELLOW));
    }

    private void updateOfferStatus(int offerId, String status) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                    .prepareStatement("UPDATE auction_offers SET status = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setInt(2, offerId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    public void markCollected(int id) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                    .prepareStatement("UPDATE auctions SET collected = 1 WHERE id = ?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in AuctionManager", e);
            }
        });
    }

    // Helper methods for Item Serialization
    private String itemToBase64(ItemStack item) {
        return Base64Coder.encodeLines(item.serializeAsBytes());
    }

    private ItemStack itemFromBase64(String data) {
        return ItemStack.deserializeBytes(Base64Coder.decodeLines(data));
    }
}

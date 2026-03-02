package com.aureleconomy.orders;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OrderManager {

    private final AurelEconomy plugin;
    private final Map<Integer, BuyOrder> activeOrders = new ConcurrentHashMap<>();
    private final Map<String, Double> lastSoldPrices = new ConcurrentHashMap<>();

    public OrderManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    public void loadOrders() {
        activeOrders.clear();
        String sql = "SELECT * FROM buy_orders WHERE status = 'ACTIVE'";
        Connection conn = plugin.getDatabaseManager().getConnection();
        try (PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int id = rs.getInt("id");
                UUID buyerUuid = UUID.fromString(rs.getString("buyer_uuid"));
                Material material = Material.valueOf(rs.getString("material"));
                int requested = rs.getInt("amount_requested");
                int filled = rs.getInt("amount_filled");
                double price = rs.getDouble("price_per_piece");
                String status = rs.getString("status");

                BuyOrder order = new BuyOrder(id, buyerUuid, material, requested, filled, price, status);
                activeOrders.put(id, order);
            }
            plugin.getComponentLogger().info("Loaded " + activeOrders.size() + " active buy orders.");
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getComponentLogger().error("Failed to load buy orders", e);
        }
    }

    public List<BuyOrder> getActiveOrders() {
        List<BuyOrder> list = new ArrayList<>(activeOrders.values());
        // Sort highest price first
        list.sort((o1, o2) -> Double.compare(o2.getPricePerPiece(), o1.getPricePerPiece()));
        return list;
    }

    public List<BuyOrder> getOrdersByPlayer(UUID uuid) {
        return new java.util.ArrayList<>(activeOrders.values().stream()
                .filter(order -> order.getBuyerUuid().equals(uuid))
                .toList());
    }

    public void createOrder(Player player, Material material, int amount, double pricePerPiece) {
        // Enforce max active orders per player
        int maxOrders = plugin.getConfig().getInt("buy-orders.max-active-orders-per-player", 10);
        if (maxOrders != -1) {
            long currentOrderCount = getOrdersByPlayer(player.getUniqueId()).size();
            if (currentOrderCount >= maxOrders) {
                player.sendMessage(
                        Component.text("You have reached the maximum active buy orders limit (" + maxOrders + ").",
                                NamedTextColor.RED));
                return;
            }
        }

        // Enforce minimum price per piece
        double minPrice = plugin.getConfig().getDouble("buy-orders.min-price-per-piece", 0.1);
        if (pricePerPiece < minPrice) {
            player.sendMessage(Component.text(
                    "The minimum price per piece is " + plugin.getEconomyManager().format(minPrice) + ".",
                    NamedTextColor.RED));
            return;
        }

        double totalCost = amount * pricePerPiece;

        // Calculate Creation Fee
        double feePercent = plugin.getConfig().getDouble("buy-orders.creation-fee-percent", 1.0);
        double feeAmount = (feePercent / 100.0) * totalCost;
        double totalToDeduct = totalCost + feeAmount;

        if (!plugin.getEconomyManager().has(Bukkit.getOfflinePlayer(player.getUniqueId()), totalToDeduct)) {
            player.sendMessage(Component.text("You do not have enough funds! You need "
                    + plugin.getEconomyManager().format(totalToDeduct) + " (includes " + feePercent + "% fee).",
                    NamedTextColor.RED));
            return;
        }

        // Deduct money upfront (escrow + non-refundable fee)
        plugin.getEconomyManager().withdraw(Bukkit.getOfflinePlayer(player.getUniqueId()), totalToDeduct);
        if (feeAmount > 0) {
            player.sendMessage(
                    Component.text("Paid a creation fee of " + plugin.getEconomyManager().format(feeAmount) + ".",
                            NamedTextColor.GRAY, TextDecoration.ITALIC));
        }

        String sql = "INSERT INTO buy_orders (buyer_uuid, material, amount_requested, price_per_piece) VALUES (?, ?, ?, ?)";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Connection conn = plugin.getDatabaseManager().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, material.name());
                ps.setInt(3, amount);
                ps.setDouble(4, pricePerPiece);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        BuyOrder order = new BuyOrder(id, player.getUniqueId(), material, amount, 0, pricePerPiece,
                                "ACTIVE");

                        // Sync back to main thread to add to memory
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            activeOrders.put(id, order);
                            player.sendMessage(Component.text(
                                    "Successfully placed buy order for " + amount + "x "
                                            + material.name().replace("_", " ") + " at "
                                            + plugin.getEconomyManager().format(pricePerPiece) + " each.",
                                    NamedTextColor.GREEN));
                        });
                    }
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Failed to insert buy order", e);
                // Refund escrow + fee on database error
                plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(player.getUniqueId()), totalToDeduct);
                player.sendMessage(
                        Component.text("A database error occurred. Your money has been refunded.", NamedTextColor.RED));
            }
        });
    }

    public void cancelOrder(Player player, int orderId) {
        BuyOrder order = activeOrders.get(orderId);
        if (order == null || !order.getBuyerUuid().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("Order not found or you don't own it.", NamedTextColor.RED));
            return;
        }

        order.setStatus("CANCELLED");
        activeOrders.remove(orderId);

        // Calculate refund
        int remainingItems = order.getAmountRemaining();
        double refundAmount = remainingItems * order.getPricePerPiece();
        plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(player.getUniqueId()), refundAmount);

        updateOrderStatusInDB(orderId, "CANCELLED", order.getAmountFilled());

        player.sendMessage(
                Component.text("Order cancelled. Refunded " + plugin.getEconomyManager().format(refundAmount) + ".",
                        NamedTextColor.GREEN));
    }

    public void fillOrder(Player seller, int orderId, int amountToSell) {
        BuyOrder order = activeOrders.get(orderId);
        if (order == null)
            return;

        if (amountToSell > order.getAmountRemaining()) {
            amountToSell = order.getAmountRemaining();
        }

        if (amountToSell <= 0)
            return;

        // Verify seller has the items
        int playerHas = countItems(seller, order.getMaterial());
        if (playerHas < amountToSell) {
            seller.sendMessage(Component.text(
                    "You don't have enough " + order.getMaterial().name().replace("_", " ") + " in your inventory.",
                    NamedTextColor.RED));
            return;
        }

        // Process Transaction
        removeItems(seller, order.getMaterial(), amountToSell);
        double rawPayout = amountToSell * order.getPricePerPiece();
        double salesTaxPercent = plugin.getConfig().getDouble("buy-orders.sales-tax-percent", 5.0);
        double taxAmount = (salesTaxPercent / 100.0) * rawPayout;
        double finalPayout = rawPayout - taxAmount;

        plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(seller.getUniqueId()), finalPayout);

        // Update order status
        order.setAmountFilled(order.getAmountFilled() + amountToSell);

        if (order.getAmountRemaining() <= 0) {
            order.setStatus("COMPLETED");
            activeOrders.remove(orderId);
            updateOrderStatusInDB(orderId, "COMPLETED", order.getAmountFilled());
        } else {
            updateOrderStatusInDB(orderId, "ACTIVE", order.getAmountFilled());
        }

        // Deliver items to buyer's collection bin
        ItemStack toDeliver = new ItemStack(order.getMaterial(), amountToSell);
        plugin.getAuctionManager().sendToCollectionBin(order.getBuyerUuid(), toDeliver);

        seller.sendMessage(Component.text("You sold " + amountToSell + "x items to a buy order for "
                + plugin.getEconomyManager().format(finalPayout), NamedTextColor.GREEN)
                .append(Component.text(" (Tax: " + plugin.getEconomyManager().format(taxAmount) + ")",
                        NamedTextColor.GRAY,
                        TextDecoration.ITALIC)));

        // Notify buyer (online or offline)
        String itemName = order.getMaterial().name().replace("_", " ");
        final int soldAmount = amountToSell;
        Player buyer = Bukkit.getPlayer(order.getBuyerUuid());
        if (buyer != null && buyer.isOnline()) {
            buyer.sendMessage(Component.text(soldAmount + "x " + itemName, NamedTextColor.AQUA)
                    .append(Component.text(" was delivered to your ", NamedTextColor.GREEN))
                    .append(Component.text("/ah collect", NamedTextColor.GOLD))
                    .append(Component.text(" bin!", NamedTextColor.GREEN)));
        } else {
            // Store notification for when they come online
            recordOfflineOrderNotification(order.getBuyerUuid(), soldAmount, itemName);
        }

        // Track last sold price for non-market items
        lastSoldPrices.put(order.getMaterial().name(), order.getPricePerPiece());
    }

    public Double getLastSoldPrice(String materialKey) {
        return lastSoldPrices.get(materialKey);
    }

    private void recordOfflineOrderNotification(UUID buyerUuid, int amount, String itemName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Connection conn = plugin.getDatabaseManager().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO offline_earnings (uuid, amount, item_display, timestamp) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, buyerUuid.toString());
                ps.setDouble(2, 0); // No currency earned, this is an item delivery notification
                ps.setString(3, amount + "x " + itemName + " (Buy Order)");
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Failed to record offline order notification", e);
            }
        });
    }

    private void updateOrderStatusInDB(int orderId, String status, int amountFilled) {
        String sql = "UPDATE buy_orders SET status = ?, amount_filled = ? WHERE id = ?";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            Connection conn = plugin.getDatabaseManager().getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                ps.setInt(2, amountFilled);
                ps.setInt(3, orderId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Failed to update order status", e);
            }
        });
    }

    private int countItems(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, Material material, int amountToRemove) {
        int leftToRemove = amountToRemove;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == material) {
                if (item.getAmount() <= leftToRemove) {
                    leftToRemove -= item.getAmount();
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(item.getAmount() - leftToRemove);
                    leftToRemove = 0;
                }
                if (leftToRemove <= 0)
                    break;
            }
        }
        player.updateInventory();
    }
}

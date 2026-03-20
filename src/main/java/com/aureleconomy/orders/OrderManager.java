package com.aureleconomy.orders;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Material;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class OrderManager {

    private final AurelEconomy plugin;
    private final Map<Integer, BuyOrder> activeOrders = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> lastSoldPrices = new ConcurrentHashMap<>();

    public OrderManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    public void loadOrders() {
        activeOrders.clear();
        String sql = "SELECT * FROM buy_orders WHERE status = 'ACTIVE'";
        try (Connection conn = plugin.getDatabaseManager().getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int id = rs.getInt("id");
                UUID buyerUuid = UUID.fromString(rs.getString("buyer_uuid"));
                Material material = Material.valueOf(rs.getString("material"));
                int requested = rs.getInt("amount_requested");
                int filled = rs.getInt("amount_filled");
                BigDecimal price = rs.getBigDecimal("price_per_piece");
                String currency = "Aurels";
                try {
                    currency = rs.getString("currency");
                    if (currency == null)
                        currency = "Aurels";
                } catch (SQLException ignored) {
                }
                String status = rs.getString("status");

                BuyOrder order = new BuyOrder(id, buyerUuid, material, requested, filled, price, currency, status);
                activeOrders.put(id, order);
            }
            plugin.getComponentLogger().info("Loaded " + activeOrders.size() + " active buy orders.");
        } catch (SQLException | IllegalArgumentException e) {
            plugin.getComponentLogger().error("Failed to load buy orders", e);
        }
    }

    public List<BuyOrder> getActiveOrders() {
        List<BuyOrder> list = new ArrayList<>(activeOrders.values());
        list.sort((o1, o2) -> o2.getPricePerPiece().compareTo(o1.getPricePerPiece()));
        return list;
    }

    public List<BuyOrder> getOrdersByPlayer(UUID uuid) {
        return new java.util.ArrayList<>(activeOrders.values().stream()
                .filter(order -> order.getBuyerUuid().equals(uuid))
                .toList());
    }

    public void createOrder(Player player, Material material, int amount, BigDecimal pricePerPiece, String currency) {
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

        if (plugin.getMarketManager().isBlacklisted(material)) {
            player.sendMessage(Component.text("This item is blacklisted and cannot be ordered.", NamedTextColor.RED));
            return;
        }

        BigDecimal minPrice = BigDecimal.valueOf(plugin.getConfig().getDouble("buy-orders.min-price-per-piece", 0.1));
        if (pricePerPiece.compareTo(minPrice) < 0) {
            player.sendMessage(Component.text(
                    "The minimum price per piece is "
                            + plugin.getEconomyManager().getFormattedWithSymbol(minPrice, currency) + ".",
                    NamedTextColor.RED));
            return;
        }

        BigDecimal totalCost = pricePerPiece.multiply(BigDecimal.valueOf(amount));

        BigDecimal feePercent = BigDecimal.valueOf(plugin.getConfig().getDouble("buy-orders.creation-fee-percent", 1.0));
        BigDecimal feeAmount = totalCost.multiply(feePercent).divide(BigDecimal.valueOf(100), RoundingMode.HALF_UP);
        BigDecimal totalToDeduct = totalCost.add(feeAmount);

        if (!plugin.getEconomyManager().has(Bukkit.getOfflinePlayer(player.getUniqueId()), totalToDeduct, currency)) {
            player.sendMessage(Component.text("You do not have enough funds! You need "
                    + plugin.getEconomyManager().getFormattedWithSymbol(totalToDeduct, currency) + " (includes "
                    + feePercent
                    + "% fee).",
                    NamedTextColor.RED));
            return;
        }

        plugin.getEconomyManager().withdraw(Bukkit.getOfflinePlayer(player.getUniqueId()), totalToDeduct, currency);
        if (feeAmount.compareTo(BigDecimal.ZERO) > 0) {
            player.sendMessage(
                    Component.text(
                            "Paid a creation fee of "
                                    + plugin.getEconomyManager().getFormattedWithSymbol(feeAmount, currency) + ".",
                            NamedTextColor.GRAY, TextDecoration.ITALIC));
        }

        String sql = "INSERT INTO buy_orders (buyer_uuid, material, amount_requested, price_per_piece, currency) VALUES (?, ?, ?, ?, ?)";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {

                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, material.name());
                ps.setInt(3, amount);
                ps.setBigDecimal(4, pricePerPiece);
                ps.setString(5, currency);
                ps.executeUpdate();

                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        BuyOrder order = new BuyOrder(id, player.getUniqueId(), material, amount, 0, pricePerPiece,
                                currency, "ACTIVE");

                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            activeOrders.put(id, order);
                            player.sendMessage(Component.text(
                                    "Successfully placed buy order for " + amount + "x "
                                            + material.name().replace("_", " ") + " at "
                                            + plugin.getEconomyManager().getFormattedWithSymbol(pricePerPiece, currency)
                                            + " each.",
                                    NamedTextColor.GREEN));
                        });
                    }
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Failed to insert buy order", e);
                plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(player.getUniqueId()), totalToDeduct,
                        currency);
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

        int remainingItems = order.getAmountRemaining();
        BigDecimal refundAmount = order.getPricePerPiece().multiply(BigDecimal.valueOf(remainingItems));
        plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(player.getUniqueId()), refundAmount,
                order.getCurrency());

        updateOrderStatusInDB(orderId, "CANCELLED", order.getAmountFilled());

        player.sendMessage(
                Component.text(
                        "Order cancelled. Refunded "
                                + plugin.getEconomyManager().getFormattedWithSymbol(refundAmount, order.getCurrency())
                                + ".",
                        NamedTextColor.GREEN));
    }

    public void fillOrder(Player seller, int orderId, int amountToSell) {
        BuyOrder order = activeOrders.get(orderId);
        if (order == null) {
            seller.sendMessage(Component.text("Order not found or already completed.", NamedTextColor.RED));
            return;
        }

        if (order.getBuyerUuid().equals(seller.getUniqueId())) {
            seller.sendMessage(Component.text("You cannot fulfill your own buy order.", NamedTextColor.RED));
            return;
        }

        int initialRemaining = order.getAmountRemaining();
        if (amountToSell > initialRemaining) {
            amountToSell = initialRemaining;
        }

        if (amountToSell <= 0) {
            seller.sendMessage(Component.text("This order is already filled!", NamedTextColor.RED));
            return;
        }

        int playerHas = countItems(seller, order.getMaterial());
        if (playerHas < amountToSell) {
            seller.sendMessage(Component.text(
                    "You don't have enough " + order.getMaterial().name().replace("_", " ") + " in your inventory.",
                    NamedTextColor.RED));
            return;
        }

        final int finalAmountToSell = amountToSell;
        final Material material = order.getMaterial();
        final UUID buyerUuid = order.getBuyerUuid();
        final BigDecimal pricePerPiece = order.getPricePerPiece();
        final String currency = order.getCurrency();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean success = false;
            int actualFilled = 0;

            String sql = "UPDATE buy_orders SET amount_filled = amount_filled + ?, " +
                    "status = CASE WHEN amount_filled + ? >= amount_requested THEN 'COMPLETED' ELSE 'ACTIVE' END " +
                    "WHERE id = ? AND status = 'ACTIVE' AND amount_filled + ? <= amount_requested";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, finalAmountToSell);
                ps.setInt(2, finalAmountToSell);
                ps.setInt(3, orderId);
                ps.setInt(4, finalAmountToSell);

                int rows = ps.executeUpdate();
                if (rows > 0) {
                    success = true;
                    actualFilled = finalAmountToSell;
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Failed atomic order fulfillment for ID: " + orderId, e);
            }

            final boolean finalSuccess = success;
            final int finalFilled = actualFilled;

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!finalSuccess) {
                    seller.sendMessage(Component.text(
                            "Failed to fulfill order. It may have been filled by someone else or a server error occurred.",
                            NamedTextColor.RED));
                    refreshOrderMemory(orderId);
                    return;
                }

                int currentHas = countItems(seller, material);
                if (currentHas < finalFilled) {
                    rollbackFulfillment(orderId, finalFilled);
                    seller.sendMessage(
                            Component.text("Fulfillment failed: Items no longer in inventory.", NamedTextColor.RED));
                    return;
                }

                removeItems(seller, material, finalFilled);
                BigDecimal rawPayout = pricePerPiece.multiply(BigDecimal.valueOf(finalFilled));
                BigDecimal salesTaxPercent = BigDecimal
                        .valueOf(plugin.getConfig().getDouble("buy-orders.sales-tax-percent", 5.0));
                BigDecimal taxAmount = rawPayout.multiply(salesTaxPercent).divide(BigDecimal.valueOf(100),
                        RoundingMode.HALF_UP);
                BigDecimal finalPayout = rawPayout.subtract(taxAmount);

                plugin.getEconomyManager().deposit(Bukkit.getOfflinePlayer(seller.getUniqueId()), finalPayout,
                        currency);

                order.setAmountFilled(order.getAmountFilled() + finalFilled);
                if (order.getAmountRemaining() <= 0) {
                    order.setStatus("COMPLETED");
                    activeOrders.remove(orderId);
                }

                ItemStack toDeliver = new ItemStack(material, finalFilled);
                plugin.getAuctionManager().sendToCollectionBin(buyerUuid, toDeliver);

                seller.sendMessage(Component.text("You sold " + finalFilled + "x items to a buy order for "
                        + plugin.getEconomyManager().getFormattedWithSymbol(finalPayout, currency),
                        NamedTextColor.GREEN)
                        .append(Component.text(
                                " (Tax: " + plugin.getEconomyManager().getFormattedWithSymbol(taxAmount, currency)
                                        + ")",
                                NamedTextColor.GRAY,
                                TextDecoration.ITALIC)));

                String itemName = material.name().replace("_", " ");
                Player buyer = Bukkit.getPlayer(buyerUuid);
                if (buyer != null && buyer.isOnline()) {
                    buyer.sendMessage(Component.text(finalFilled + "x " + itemName, NamedTextColor.AQUA)
                            .append(Component.text(" was delivered to your ", NamedTextColor.GREEN))
                            .append(Component.text("/ah collect", NamedTextColor.GOLD))
                            .append(Component.text(" bin!", NamedTextColor.GREEN)));
                } else {
                    recordOfflineOrderNotification(buyerUuid, finalFilled, itemName);
                }

                lastSoldPrices.put(material.name(), pricePerPiece);
            });
        });
    }

    private void rollbackFulfillment(int orderId, int amountToSubtract) {
        String sql = "UPDATE buy_orders SET amount_filled = amount_filled - ?, status = 'ACTIVE' WHERE id = ?";
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, amountToSubtract);
                ps.setInt(2, orderId);
                ps.executeUpdate();
                refreshOrderMemory(orderId);
            } catch (SQLException e) {
                plugin.getComponentLogger().error("CRITICAL: Failed to rollback fulfillment for order " + orderId, e);
            }
        });
    }

    private void refreshOrderMemory(int orderId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement ps = conn.prepareStatement("SELECT * FROM buy_orders WHERE id = ?")) {
                ps.setInt(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("id");
                        UUID buyerUuid = UUID.fromString(rs.getString("buyer_uuid"));
                        Material material = Material.valueOf(rs.getString("material"));
                        int requested = rs.getInt("amount_requested");
                        int filled = rs.getInt("amount_filled");
                        BigDecimal price = rs.getBigDecimal("price_per_piece");
                        String currency = rs.getString("currency");
                        String status = rs.getString("status");

                        BuyOrder updated = new BuyOrder(id, buyerUuid, material, requested, filled, price, currency,
                                status);
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (status.equals("ACTIVE")) {
                                activeOrders.put(id, updated);
                            } else {
                                activeOrders.remove(id);
                            }
                        });
                    }
                }
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Failed to refresh order memory", e);
            }
        });
    }

    public BigDecimal getLastSoldPrice(String materialKey) {
        return lastSoldPrices.get(materialKey);
    }

    private void recordOfflineOrderNotification(UUID buyerUuid, int amount, String itemName) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO offline_earnings (uuid, amount, item_display, timestamp) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, buyerUuid.toString());
                ps.setBigDecimal(2, BigDecimal.ZERO); // No currency earned, this is an item delivery notification
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
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
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

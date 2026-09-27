package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.orders.BuyOrder;
import com.aureleconomy.webstore.PurchaseQueue;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Drains browser-submitted actions from a {@link PurchaseQueue} on the main thread.
 *
 * <p>The dashboard POSTs a purchase and immediately starts polling
 * {@code purchase-status}, exactly as it does against the standalone backend.
 * Keeping execution here means every Bukkit API call stays on the main thread
 * instead of an HTTP worker, and a purchase is only marked completed once it has
 * actually been charged and delivered.
 *
 * <p>{@code fill-order} is the one multi-step action: {@code OrderManager
 * .fillOrder} completes asynchronously and signals failure only by chat
 * message, so it is split across two drain passes and judged by whether the
 * order's persisted fill counter moved. Nothing here ever blocks the tick.
 */
public class WebPurchaseExecutor {

    private static final long TICK_INTERVAL = 10L;      // twice a second
    private static final long PURCHASE_TTL_MS = 30 * 60 * 1000L;
    private static final long FILL_ATTEMPT_TTL_MS = 15_000L;

    /** A fill-order that has been dispatched but not yet resolved. */
    private static final class PendingFill {
        final UUID player;
        final int orderId;
        final int before;
        final long startedAt;

        PendingFill(UUID player, int orderId, int before) {
            this.player = player;
            this.orderId = orderId;
            this.before = before;
            this.startedAt = System.currentTimeMillis();
        }
    }

    private final AurelEconomy plugin;
    private final PurchaseQueue queue;
    private final Map<String, PendingFill> pendingFills = new HashMap<>();
    private BukkitTask task;

    public WebPurchaseExecutor(AurelEconomy plugin, PurchaseQueue queue) {
        this.plugin = plugin;
        this.queue = queue;
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::drain, TICK_INTERVAL, TICK_INTERVAL);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        pendingFills.clear();
    }

    private void drain() {
        try {
            resolvePendingFills();
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (PurchaseQueue.Purchase purchase : queue.claimPending(player.getUniqueId())) {
                    execute(player, purchase);
                }
            }
            queue.prune(PURCHASE_TTL_MS);
        } catch (Exception e) {
            plugin.getComponentLogger().error("Web purchase drain failed", e);
        }
    }

    private void resolvePendingFills() {
        long now = System.currentTimeMillis();
        pendingFills.entrySet().removeIf(entry -> {
            PendingFill pending = entry.getValue();
            BuyOrder order = findOrder(pending.orderId);
            if (order == null) {
                queue.complete(entry.getKey(), false, errorResult("Order could not be filled"));
                return true;
            }
            if (order.getAmountFilled() != pending.before) {
                JsonObject result = new JsonObject();
                result.addProperty("success", true);
                result.addProperty("filled", order.getAmountFilled() - pending.before);
                queue.complete(entry.getKey(), true, result.toString());
                return true;
            }
            if (now - pending.startedAt > FILL_ATTEMPT_TTL_MS) {
                queue.complete(entry.getKey(), false, errorResult("Order could not be filled"));
                return true;
            }
            return false;
        });
    }

    private static String errorResult(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("error", message);
        return result.toString();
    }

    private void execute(Player player, PurchaseQueue.Purchase purchase) {
        JsonObject result = new JsonObject();
        boolean ok;
        try {
            ok = switch (purchase.type()) {
                case BUY -> buy(player, purchase, result);
                case SELL -> sell(player, purchase, result);
                case BID -> bid(player, purchase, result);
                case FILL_ORDER -> startFillOrder(player, purchase, result);
            };
        } catch (Exception e) {
            plugin.getComponentLogger().error("Web purchase " + purchase.id() + " failed", e);
            result = new JsonObject();
            result.addProperty("error", "Purchase failed");
            ok = false;
        }
        if (ok) {
            queue.complete(purchase.id(), true, result.toString());
        } else if (!queue.find(purchase.id()).status().equals("processing")) {
            queue.complete(purchase.id(), false, result.toString());
        }
        // A deferred fill-order stays "processing" and is resolved next pass.
    }

    // ── server market ─────────────────────────────────────────────────

    private boolean buy(Player buyer, PurchaseQueue.Purchase purchase, JsonObject result) {
        String itemKey = purchase.itemKey();
        int amount = purchase.amount();

        Material material;
        BigDecimal buyPrice;
        String currency;
        try {
            material = Material.valueOf(itemKey.toUpperCase());
            buyPrice = plugin.getMarketManager().getBuyPrice(material);
            currency = plugin.getMarketManager().getCurrency(material);
        } catch (IllegalArgumentException e) {
            // Custom spawner key rather than a material name.
            buyPrice = plugin.getMarketManager().getBuyPrice(itemKey);
            currency = plugin.getMarketManager().getCurrency(itemKey);
            material = Material.SPAWNER;
        }

        if (plugin.getMarketManager().isBlacklisted(material)) {
            return fail(result, "This item cannot be bought from the web");
        }
        if (buyPrice == null || buyPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return fail(result, "Item not for sale");
        }

        BigDecimal totalCost = buyPrice.multiply(BigDecimal.valueOf(amount));
        if (!plugin.getEconomyManager().has(buyer, totalCost, currency)) {
            return fail(result, "Not enough funds. You need "
                    + plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency));
        }

        ItemStack toGive = new ItemStack(material, amount);
        if (!com.aureleconomy.utils.InventoryUtils.hasSpace(buyer.getInventory(), toGive, amount)) {
            return fail(result, "Your inventory is full");
        }

        plugin.getEconomyManager().withdraw(buyer, totalCost, currency);
        buyer.getInventory().addItem(toGive);
        plugin.getMarketManager().onTransaction(itemKey, true, amount);

        BigDecimal newBalance = plugin.getEconomyManager().getBalance(buyer, currency);
        String spent = plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency);

        result.addProperty("success", true);
        result.addProperty("amount", amount);
        result.addProperty("spent", spent);
        result.addProperty("newBalance", newBalance.doubleValue());
        result.addProperty("newBalanceFormatted",
                plugin.getEconomyManager().getFormattedWithSymbol(newBalance, currency));
        result.addProperty("currency", currency);

        buyer.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<green><bold>✔</bold> Web purchase: <white>" + amount + "x "
                        + material.name().replace("_", " ") + "</white> for <gold>" + spent
                        + "</gold></green>"));
        return true;
    }

    /**
     * The mirror of {@link #buy}: the player hands over items and is credited at
     * the market's sell price. Items are only removed after every check passes,
     * so a rejected sell can never eat stock.
     */
    private boolean sell(Player seller, PurchaseQueue.Purchase purchase, JsonObject result) {
        String itemKey = purchase.itemKey();
        int amount = purchase.amount();

        Material material;
        BigDecimal sellPrice;
        String currency;
        try {
            material = Material.valueOf(itemKey.toUpperCase());
            sellPrice = plugin.getMarketManager().getSellPrice(material);
            currency = plugin.getMarketManager().getCurrency(material);
        } catch (IllegalArgumentException e) {
            // Custom spawner key rather than a material name.
            sellPrice = plugin.getMarketManager().getSellPrice(itemKey);
            currency = plugin.getMarketManager().getCurrency(itemKey);
            material = Material.SPAWNER;
        }

        if (plugin.getMarketManager().isBlacklisted(material)) {
            return fail(result, "This item cannot be sold from the web");
        }
        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return fail(result, "The market is not buying this item");
        }

        int held = countInInventory(seller, material);
        if (held < amount) {
            return fail(result, "You only have " + held + "x "
                    + material.name().replace("_", " "));
        }

        BigDecimal payout = sellPrice.multiply(BigDecimal.valueOf(amount));
        removeFromInventory(seller, material, amount);
        plugin.getEconomyManager().deposit(seller, payout, currency);
        // Records the volume and moves the price, exactly like the in-game sell.
        plugin.getMarketManager().onTransaction(itemKey, false, amount);

        BigDecimal newBalance = plugin.getEconomyManager().getBalance(seller, currency);
        String earned = plugin.getEconomyManager().getFormattedWithSymbol(payout, currency);

        result.addProperty("success", true);
        result.addProperty("amount", amount);
        result.addProperty("earned", earned);
        result.addProperty("newBalance", newBalance.doubleValue());
        result.addProperty("newBalanceFormatted",
                plugin.getEconomyManager().getFormattedWithSymbol(newBalance, currency));
        result.addProperty("currency", currency);

        seller.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                "<green><bold>✔</bold> Web sale: <white>" + amount + "x "
                        + material.name().replace("_", " ") + "</white> for <gold>" + earned
                        + "</gold></green>"));
        return true;
    }

    /** Remove {@code amount} of {@code material} from the player's inventory. */
    private void removeFromInventory(Player player, Material material, int amount) {
        int left = amount;
        var contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (stack.getAmount() == take) {
                player.getInventory().setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                player.getInventory().setItem(slot, stack);
            }
        }
    }

    // ── auction house ─────────────────────────────────────────────────

    private boolean bid(Player bidder, PurchaseQueue.Purchase purchase, JsonObject result) {
        AuctionItem ai = plugin.getAuctionManager().getAuctionById(purchase.auctionId());
        if (ai == null || ai.isEnded()) {
            return fail(result, "Auction expired or doesn't exist");
        }
        if (ai.getSeller().equals(bidder.getUniqueId())) {
            return fail(result, "You cannot bid on your own auction");
        }

        BigDecimal amount = BigDecimal.valueOf(purchase.amount());

        if (ai.isBin()) {
            // A BIN listing is a purchase, not a bid.
            int quantity = ai.getPurchaseMode() == AuctionItem.PurchaseMode.STACK
                    ? ai.getAvailableQuantity() : purchase.amount();
            BigDecimal totalCost = ai.getPricePerUnit().multiply(BigDecimal.valueOf(quantity))
                    .setScale(2, RoundingMode.HALF_UP);
            if (!plugin.getEconomyManager().has(bidder, totalCost, ai.getCurrency())) {
                return fail(result, "Not enough funds. You need "
                        + plugin.getEconomyManager().getFormattedWithSymbol(totalCost, ai.getCurrency()));
            }
            int purchased = plugin.getAuctionManager().purchaseUnits(ai, bidder, quantity);
            if (purchased <= 0) {
                return fail(result, "Purchase failed");
            }
            BigDecimal newBal = plugin.getEconomyManager().getBalance(bidder, ai.getCurrency());
            result.addProperty("success", true);
            result.addProperty("purchased", purchased);
            result.addProperty("spent",
                    plugin.getEconomyManager().getFormattedWithSymbol(totalCost, ai.getCurrency()));
            result.addProperty("newBalance", newBal.doubleValue());
            return true;
        }

        if (amount.compareTo(ai.getPrice()) < 0) {
            return fail(result, "Bid too low");
        }
        if (amount.compareTo(ai.getPrice()) == 0 && ai.getHighestBidder() != null) {
            return fail(result, "Your bid must be higher than the current bid");
        }
        if (!plugin.getEconomyManager().has(bidder, amount, ai.getCurrency())) {
            return fail(result, "Not enough funds. You need "
                    + plugin.getEconomyManager().getFormattedWithSymbol(amount, ai.getCurrency()));
        }

        plugin.getAuctionManager().bid(ai, bidder.getUniqueId(), amount);

        result.addProperty("success", true);
        result.addProperty("amount", amount.doubleValue());
        result.addProperty("currency", ai.getCurrency());
        return true;
    }

    // ── buy orders ────────────────────────────────────────────────────

    private boolean startFillOrder(Player filler, PurchaseQueue.Purchase purchase, JsonObject result) {
        int orderId = purchase.orderId();
        BuyOrder order = findOrder(orderId);
        if (order == null) {
            return fail(result, "Order not found or already completed");
        }
        if (order.getBuyerUuid().equals(filler.getUniqueId())) {
            return fail(result, "You cannot fulfill your own buy order");
        }

        int available = countInInventory(filler, order.getMaterial());
        if (available <= 0) {
            return fail(result, "You do not have any "
                    + order.getMaterial().name().replace("_", " ") + " in your inventory");
        }

        PendingFill pending = new PendingFill(filler.getUniqueId(), orderId, order.getAmountFilled());
        pendingFills.put(purchase.id(), pending);

        plugin.getOrderManager().fillOrder(filler, orderId, Math.min(purchase.amount(), available));
        return true;
    }

    private BuyOrder findOrder(int orderId) {
        for (BuyOrder order : plugin.getOrderManager().getActiveOrders()) {
            if (order.getId() == orderId) {
                return order;
            }
        }
        return null;
    }

    private int countInInventory(Player player, Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private boolean fail(JsonObject result, String message) {
        result.addProperty("error", message);
        return false;
    }
}

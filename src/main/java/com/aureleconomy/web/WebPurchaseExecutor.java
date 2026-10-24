package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
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
    private static final long SETTLED_RETENTION_MS = 30 * 60 * 1000L;
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
    private final long fillAttemptTtlMs;
    private BukkitTask task;

    public WebPurchaseExecutor(AurelEconomy plugin, PurchaseQueue queue) {
        this(plugin, queue, FILL_ATTEMPT_TTL_MS);
    }

    /** Test seam: shrink the fill-attempt window so a resolution can be driven. */
    WebPurchaseExecutor(AurelEconomy plugin, PurchaseQueue queue, long fillAttemptTtlMs) {
        this.plugin = plugin;
        this.queue = queue;
        this.fillAttemptTtlMs = Math.max(0L, fillAttemptTtlMs);
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

    void drain() {
        try {
            // Cancel before claiming. An entry that waited past the TTL must not
            // run now, at a price the player saw days ago; this also cancels the
            // entries of players who are not online, which the claim loop below
            // never visits.
            queue.expireStale();
            resolvePendingFills();
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (PurchaseQueue.Purchase purchase : queue.claimPending(player.getUniqueId())) {
                    execute(player, purchase);
                }
            }
            queue.prune(SETTLED_RETENTION_MS);
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
            if (now - pending.startedAt > fillAttemptTtlMs) {
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
        if (!player.isOnline()) {
            // Claimed and then the player left before this pass ran. Nothing has
            // been charged yet, so cancelling is enough.
            queue.complete(purchase.id(), false,
                    errorResult("The player went offline before the purchase could run."));
            return;
        }
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
        if (ok && purchase.type() == PurchaseQueue.Type.FILL_ORDER) {
            // OrderManager.fillOrder completes asynchronously and reports failure
            // only through chat. Settling here would answer "failed" before the
            // fill had a chance to move the order's counter (M7). The row stays
            // processing and resolvePendingFills settles it on a later pass.
            return;
        }
        queue.complete(purchase.id(), ok, result.toString());
    }

    // ── server market ─────────────────────────────────────────────────

    private boolean buy(Player buyer, PurchaseQueue.Purchase purchase, JsonObject result) {
        String itemKey = purchase.itemKey();
        int amount = purchase.amount();

        // A disabled custom item is not for sale, whatever its cached price
        // says. The registry is the authority and the price map is a cache of
        // it, so the check belongs here rather than in the price lookup (H7).
        com.aureleconomy.scanner.CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (registry != null) {
            com.aureleconomy.scanner.CustomMarketItem custom = registry.getById(itemKey);
            if (custom != null && !custom.isEnabled()) {
                return fail(result, "This item is disabled");
            }
        }

        // Resolve through the entry cache rather than guessing a material from the key.
            // Every market item, plain or named, is cached under the same key its price
            // is stored under, so a key that misses the cache has no price either. A
            // named item is every spawner and every enchanted book, and its material is
            // the same for all of them, so the material has to come from the entry.
            com.aureleconomy.market.MarketItems.MarketEntry entry =
            com.aureleconomy.market.MarketItemFactory.entryForKey(plugin, itemKey);
            if (entry == null) {
            return fail(result, "Item not for sale");
            }
            Material material = entry.material;
            BigDecimal buyPrice = plugin.getMarketManager().getBuyPrice(itemKey);
            String currency = plugin.getMarketManager().getCurrency(itemKey);

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

        ItemStack toGive = com.aureleconomy.market.MarketItemFactory
            .build(plugin, entry, amount);
            if (toGive == null) {
            return fail(result, "Item not for sale");
            }
        if (!com.aureleconomy.utils.InventoryUtils.hasSpace(buyer.getInventory(), toGive, amount)) {
            return fail(result, "Your inventory is full");
        }

        // The charge decides whether the sale happened. Delivering first and
        // charging second is how a player walks away with a free item whenever the
        // debit is refused, which is exactly what the honest withdraw now reports.
        if (!plugin.getEconomyManager().withdraw(buyer, totalCost, currency)) {
            return fail(result, "Not enough funds. You need "
            + plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency));
        }
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

        // Resolve through the entry cache rather than guessing a material from the key.
            // Every market item, plain or named, is cached under the same key its price
            // is stored under, so a key that misses the cache has no price either.
            com.aureleconomy.market.MarketItems.MarketEntry entry =
            com.aureleconomy.market.MarketItemFactory.entryForKey(plugin, itemKey);
            if (entry == null) {
            return fail(result, "The market is not buying this item");
            }
            Material material = entry.material;
            BigDecimal sellPrice = plugin.getMarketManager().getSellPrice(itemKey);
            String currency = plugin.getMarketManager().getCurrency(itemKey);

        if (plugin.getMarketManager().isBlacklisted(material)) {
            return fail(result, "This item cannot be sold from the web");
        }
        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return fail(result, "The market is not buying this item");
        }

        // Count and remove against the item the entry actually is, not just its
            // material. Every spawner is Material.SPAWNER and every enchanted book is
            // Material.ENCHANTED_BOOK, so a type check alone lets a bare spawner be
            // sold as an iron golem spawner at the iron golem spawner price.
            ItemStack expected = com.aureleconomy.market.MarketItemFactory.build(plugin, entry, 1);
            int held = countSimilar(seller, expected, amount);
            if (held < amount) {
            return fail(result, "You only have " + held + "x "
            + material.name().replace("_", " "));
            }
            
            BigDecimal payout = sellPrice.multiply(BigDecimal.valueOf(amount));
        // Paying first, taking second. Removing the items and then paying used to
        // mean a failed payout ate the seller's spawners for nothing, which is a
        // real outcome rather than a theoretical one: the scheduled trade volume
        // query on a shared connection has been observed failing with "the
        // database has been closed".
        if (!plugin.getEconomyManager().deposit(seller, payout, currency)) {
            return fail(result, "The payout failed, so nothing was taken from your inventory");
        }
        removeFromInventory(seller, expected, amount);
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
        private void removeFromInventory(Player player, ItemStack expected, int amount) {
        int left = amount;
        var contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
        ItemStack stack = contents[slot];
        if (stack == null || !stack.isSimilar(expected)) {
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
            // A BIN listing is a purchase, not a bid: the queued amount is the
            // quantity for a UNIT listing. It used to be the unit price, which
            // turned a listing priced 100.50 per unit into a 100-item purchase
            // (H4). A STACK listing always sells the whole stack.
            int quantity = ai.getPurchaseMode() == AuctionItem.PurchaseMode.STACK
                    ? ai.getAvailableQuantity()
                    : Math.min(purchase.amount(), ai.getAvailableQuantity());
            if (quantity <= 0) {
                return fail(result, "This listing has no items remaining.");
            }
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
            // purchaseUnits charges for what it actually takes, which can be less
            // than asked for when the stock moved. Report that same number rather
            // than the request, so the browser never shows a cost that was not
            // charged.
            BigDecimal charged = ai.getPricePerUnit().multiply(BigDecimal.valueOf(purchased))
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal newBal = plugin.getEconomyManager().getBalance(bidder, ai.getCurrency());
            result.addProperty("success", true);
            result.addProperty("purchased", purchased);
            result.addProperty("spent",
                    plugin.getEconomyManager().getFormattedWithSymbol(charged, ai.getCurrency()));
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

        AuctionManager.BidResult bidResult = plugin.getAuctionManager().bid(ai, bidder.getUniqueId(), amount);
        if (bidResult != AuctionManager.BidResult.SUCCESS) {
            // The bid charges the bidder now (C1), so a refusal is reported
            // instead of answering success for a bid that never happened.
            return fail(result, bidResult.playerMessage());
        }

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

    /**
         * How many of {@code material} the player is holding. Used only to cap how
         * much a filler is asked for; the authoritative removal happens in
         * {@code OrderManager.fillOrder}, which matches the real item.
         */
        private int countInInventory(Player player, Material material) {
            int total = 0;
            for (ItemStack stack : player.getInventory().getStorageContents()) {
                if (stack != null && stack.getType() == material) {
                    total += stack.getAmount();
                }
            }
            return total;
        }

        /**
         * How many of {@code expected} the player is holding, counting only stacks
         * that really are that item, capped at {@code needed} so a refusal cannot
         * claim more than was asked for.
         */
        private int countSimilar(Player player, ItemStack expected, int needed) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
        if (stack == null || !stack.isSimilar(expected)) {
        continue;
        }
        total += stack.getAmount();
        if (total >= needed) {
        return needed;
        }
        }
        return total;
        }

    private boolean fail(JsonObject result, String message) {
        result.addProperty("error", message);
        return false;
    }
}

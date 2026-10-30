package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.economy.LedgerReason;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Prices the stacks a player has put in the sell window and executes the sale.
 *
 * <p>Two defects share this code path, which is why they share one implementation
 * (M2, M3):
 *
 * <ul>
 *   <li>The window used to guess each stack's price key from its material name, so
 *       a spawner was looked up as {@code Iron Golem Spawner} while the price map
 *       holds {@code SPAWNER_IRON_GOLEM_SPAWNER}. Every spawner lookup missed and
 *       no spawner could be sold. Identity now comes from
 *       {@link MarketItemFactory#priceKey}, the same matcher the web sell route
 *       uses.</li>
 *   <li>The window used to quote the whole sale when it opened and then pay that
 *       quote on confirm, so a price that moved in between was ignored in either
 *       direction. The quote and the payout are now both taken from the contents
 *       and the prices at the moment of confirmation.</li>
 * </ul>
 *
 * <p>Payouts are per currency and all-or-nothing: the money is deposited before
 * the matching stacks are cleared, so a refused payout leaves that currency's
 * items in the window instead of eating them. The economy API can fail on a real
 * database error, which is exactly the case this ordering covers.
 */
public final class SellTransaction {

    private final AurelEconomy plugin;

    public SellTransaction(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    /** One stack the market will buy, priced when the quote was taken. */
    public record Line(int slot, String key, String currency, BigDecimal unitPrice, int amount) {
        public BigDecimal total() {
            return unitPrice.multiply(BigDecimal.valueOf(amount));
        }
    }

    /**
     * The sellable stacks of a window plus the count of stacks the market cannot
     * value. {@code refused} is reported to the player, because a silently
     * ignored item looks like the confirm button did nothing.
     */
    public record Quote(List<Line> lines, int refused) {
        public boolean isEmpty() {
            return lines.isEmpty();
        }

        /** The payout per currency this quote would produce. */
        public Map<String, BigDecimal> totals() {
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            for (Line line : lines) {
                totals.merge(line.currency(), line.total(), BigDecimal::add);
            }
            return totals;
        }
    }

    /** What a completed sale actually paid, per currency, and how many stacks stayed. */
    public record Sale(Map<String, BigDecimal> paid, int unsold) {
        public boolean isEmpty() {
            return paid.isEmpty();
        }
    }

    /**
     * Prices every non-empty stack in {@code contents} at the current market
     * price.
     */
    public Quote quote(List<ItemStack> contents) {
        List<Line> lines = new ArrayList<>();
        int refused = 0;
        if (contents == null) {
            return new Quote(lines, refused);
        }

        for (int slot = 0; slot < contents.size(); slot++) {
            ItemStack item = contents.get(slot);
            if (item == null || item.getType() == null || item.getType() == Material.AIR) {
                continue;
            }

            String key = MarketItemFactory.priceKey(plugin, item);
            BigDecimal price = key == null ? BigDecimal.ZERO : plugin.getMarketManager().getSellPrice(key);
            if (key == null || price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                refused++;
                continue;
            }

            String currency = plugin.getMarketManager().getCurrency(key);
            lines.add(new Line(slot, key, currency, price, item.getAmount()));
        }
        return new Quote(lines, refused);
    }

    /**
     * Sells what {@code contents} holds right now. {@code clearSlot} is called for
     * each sold slot after its currency's payout succeeded.
     */
    public Sale sell(List<ItemStack> contents, OfflinePlayer seller, IntConsumer clearSlot) {
        Quote quote = quote(contents);

        Map<String, List<Line>> byCurrency = new LinkedHashMap<>();
        for (Line line : quote.lines()) {
            byCurrency.computeIfAbsent(line.currency(), currency -> new ArrayList<>()).add(line);
        }

        Map<String, BigDecimal> paid = new LinkedHashMap<>();
        int cleared = 0;
        for (Map.Entry<String, List<Line>> group : byCurrency.entrySet()) {
            String currency = group.getKey();
            BigDecimal total = group.getValue().stream()
                    .map(Line::total)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (!plugin.getEconomyManager().deposit(seller, total, currency,
                    LedgerReason.MARKET_SELL, null, null)) {
                // Nothing is cleared for this currency, so the player keeps those items.
                continue;
            }

            for (Line line : group.getValue()) {
                clearSlot.accept(line.slot());
                plugin.getMarketManager().onTransaction(line.key(), false, line.amount());
                cleared++;
            }
            paid.put(currency, total);
        }
        return new Sale(paid, quote.refused() + (quote.lines().size() - cleared));
    }
}

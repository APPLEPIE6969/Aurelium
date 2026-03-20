package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StocksGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final Inventory inventory;
    private int page = 0;
    private String searchQuery = null;
    private final List<StockItem> stocks = new ArrayList<>();

    public StocksGUI(AurelEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 54, Component.text("📈 Market Stocks"));
        initializeStocks();
        refresh(); // Initial price load
        updateInventory();
    }

    private void initializeStocks() {
        java.util.Set<String> processedKeys = new java.util.HashSet<>();

        // Priority 1: Specific categories (Tools, Food, Minerals, etc.)
        for (Category cat : Category.values()) {
            if (cat == Category.ALL_ITEMS)
                continue;
            for (MarketEntry entry : MarketItems.getItems(cat)) {
                String key = (entry.customName != null && entry.material == Material.SPAWNER) ? entry.customName
                        : entry.material.name();
                if (processedKeys.add(key)) {
                    stocks.add(new StockItem(entry));
                }
            }
        }

        // Priority 2: ALL_ITEMS (for everything else survival-obtainable)
        for (MarketEntry entry : MarketItems.getItems(Category.ALL_ITEMS)) {
            String key = entry.material.name(); // ALL_ITEMS generally doesn't have custom names in its init
            if (processedKeys.add(key)) {
                stocks.add(new StockItem(entry));
            }
        }
    }

    private void loadPrices() {
        for (StockItem stock : stocks) {
            MarketEntry entry = stock.entry;
            BigDecimal basePrice = entry.price;
            BigDecimal currentPrice;
            BigDecimal sellPrice;

            String priceKey = (entry.customName != null)
                    ? entry.customName
                    : entry.material.name();

            currentPrice = plugin.getMarketManager().getBuyPrice(priceKey);
            sellPrice = plugin.getMarketManager().getSellPrice(priceKey);

            // If it's a non-market item (base price 1.0 from ALL_ITEMS dynamic init), check
            // last sold
            if (basePrice.compareTo(BigDecimal.ONE) == 0 && currentPrice.compareTo(BigDecimal.ONE) == 0) {
                BigDecimal lastSold = plugin.getOrderManager().getLastSoldPrice(priceKey);
                if (lastSold != null) {
                    currentPrice = lastSold;
                    sellPrice = lastSold; // No separate sell price for non-market items, just actual value
                } else {
                    currentPrice = BigDecimal.ZERO; // Unvalued
                    sellPrice = BigDecimal.ZERO;
                }
            }

            double change = 0;
            if (basePrice.compareTo(BigDecimal.ZERO) > 0 && currentPrice.compareTo(BigDecimal.ZERO) > 0 && basePrice.compareTo(BigDecimal.ONE) != 0) {
                // ((currentPrice - basePrice) / basePrice) * 100
                change = currentPrice.subtract(basePrice).divide(basePrice, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
            }

            stock.currentPrice = currentPrice;
            stock.sellPrice = sellPrice;
            stock.change = change;
        }
    }

    private void updateInventory() {
        inventory.clear();

        int itemsPerPage = 45;
        int startIndex = page * itemsPerPage;

        // Filter and sort stocks
        List<StockItem> filteredStocks = stocks.stream()
                .filter(s -> {
                    if (searchQuery == null)
                        return true;
                    String name = (s.entry.customName != null ? s.entry.customName : s.entry.material.name())
                            .toLowerCase();
                    return name.contains(searchQuery.toLowerCase());
                })
                .sorted((a, b) -> {
                    // Priority 1: Market Items vs Non-Market (ALL_ITEMS have base price 1.0)
                    boolean aIsMarket = a.entry.price.compareTo(BigDecimal.ONE) != 0;
                    boolean bIsMarket = b.entry.price.compareTo(BigDecimal.ONE) != 0;

                    if (aIsMarket && !bIsMarket)
                        return -1;
                    if (!aIsMarket && bIsMarket)
                        return 1;

                    // Priority 2: Absolute Percentage Change (Highest activity/volatility first)
                    // We sort by absolute magnitude of change descending
                    return Double.compare(Math.abs(b.change), Math.abs(a.change));
                })
                .toList();

        int maxPages = (int) Math.ceil((double) filteredStocks.size() / itemsPerPage);
        int endIndex = Math.min(startIndex + itemsPerPage, filteredStocks.size());

        int slot = 0;
        for (int i = startIndex; i < endIndex; i++) {
            StockItem stock = filteredStocks.get(i);

            ItemStack item = new ItemStack(stock.entry.material);
            ItemMeta meta = item.getItemMeta();

            // Setup proper spawner metadata if it's a spawner
            if (stock.entry.material == Material.SPAWNER && stock.entry.customName != null) {
                try {
                    org.bukkit.inventory.meta.BlockStateMeta bMeta = (org.bukkit.inventory.meta.BlockStateMeta) meta;
                    org.bukkit.block.CreatureSpawner spawner = (org.bukkit.block.CreatureSpawner) bMeta.getBlockState();
                    String mobName = stock.entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                    spawner.setSpawnedType(org.bukkit.entity.EntityType.valueOf(mobName));
                    bMeta.setBlockState(spawner);
                    meta = bMeta;
                } catch (Exception ignored) {
                }
            }

            // Color based on change
            NamedTextColor color = NamedTextColor.WHITE;
            String arrow = "▬";
            String percentageText = "";

            boolean dynamicPricing = plugin.getConfig().getBoolean("market.dynamic-pricing", true);

            if (stock.change > 0) {
                color = NamedTextColor.GREEN;
                arrow = "▲";
                if (dynamicPricing) {
                    percentageText = " +" + String.format("%.1f%%", stock.change);
                }
            } else if (stock.change < 0) {
                color = NamedTextColor.RED;
                arrow = "▼";
                if (dynamicPricing) {
                    percentageText = " " + String.format("%.1f%%", stock.change);
                }
            }

            String displayName = stock.entry.customName != null ? stock.entry.customName : stock.entry.material.name();
            meta.displayName(Component.text(displayName, color));

            List<Component> lore = new ArrayList<>();

            if (stock.entry.price.compareTo(BigDecimal.ONE) == 0) {
                // Formatting for non-market items (Prices pulled from Order/Auction history)
                if (stock.currentPrice.compareTo(BigDecimal.ZERO) > 0) {
                    lore.add(Component.text("Last Sold For: " + plugin.getEconomyManager().getFormattedWithSymbol(stock.currentPrice, plugin.getEconomyManager().getDefaultCurrency()), NamedTextColor.GOLD));
                } else {
                    lore.add(Component.text("Last Sold For: Unvalued", NamedTextColor.DARK_GRAY));
                }
                lore.add(Component.text("Base Price: Unvalued (Not in Market)", NamedTextColor.DARK_GRAY));
            } else {
                // Formatting for real market items
                lore.add(Component.text("Current Buy Price: " + plugin.getEconomyManager().getFormattedWithSymbol(stock.currentPrice, plugin.getEconomyManager().getDefaultCurrency()), NamedTextColor.GREEN));
                lore.add(Component.text("Current Sell Price: " + plugin.getEconomyManager().getFormattedWithSymbol(stock.sellPrice, plugin.getEconomyManager().getDefaultCurrency()), NamedTextColor.RED));
                lore.add(Component.text("Base Price: " + plugin.getEconomyManager().getFormattedWithSymbol(stock.entry.price, plugin.getEconomyManager().getDefaultCurrency()),
                        NamedTextColor.DARK_GRAY));

                if (dynamicPricing && stock.change != 0) {
                    lore.add(Component.text("Change: " + arrow + percentageText, color));
                } else {
                    lore.add(Component.text("Change: " + arrow, color));
                }
            }

            meta.lore(lore);
            item.setItemMeta(meta);

            inventory.setItem(slot++, item);
        }

        // Navigation Buttons
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.ARROW);
            ItemMeta meta = prev.getItemMeta();
            meta.displayName(Component.text("← Previous Page", NamedTextColor.YELLOW));
            inventory.setItem(45, prev);
        }

        // Search Button
        inventory.setItem(46, new ItemBuilder(Material.COMPASS)
                .name(Component.text(searchQuery != null ? "Change Filter: " + searchQuery : "Filter Stocks",
                        NamedTextColor.AQUA))
                .lore(Component.text("Filter items by name", NamedTextColor.GRAY)).build());

        if (page < maxPages - 1) {
            ItemStack next = new ItemStack(Material.ARROW);
            ItemMeta meta = next.getItemMeta();
            meta.displayName(Component.text("Next Page →", NamedTextColor.YELLOW));
            next.setItemMeta(meta);
            inventory.setItem(53, next);
        }

        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.displayName(Component.text("Close", NamedTextColor.RED));
        close.setItemMeta(meta);
        inventory.setItem(49, close);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        int itemSlot = event.getSlot();

        if (itemSlot == 45 && page > 0) {
            page--;
            updateInventory();
        } else if (itemSlot == 53 && page < (Math.ceil((double) stocks.size() / 45) - 1)) {
            page++;
            updateInventory();
        } else if (itemSlot == 46) {
            promptSearch();
        } else if (itemSlot == 49) {
            player.closeInventory();
        }
    }

    private void promptSearch() {
        player.closeInventory();
        player.sendMessage(
                Component.text("Type your stock filter in chat (or type 'cancel' to abort):", NamedTextColor.AQUA));
        plugin.getChatPromptManager().prompt(player, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                open();
                return;
            }
            this.searchQuery = input;
            refresh();
            open();
        });
    }

    public void open() {
        player.openInventory(inventory);
    }

    public void refresh() {
        loadPrices();
        updateInventory();
    }

    private static class StockItem {
        final MarketEntry entry;
        BigDecimal currentPrice;
        BigDecimal sellPrice;
        double change;

        StockItem(MarketEntry entry) {
            this.entry = entry;
        }
    }
}

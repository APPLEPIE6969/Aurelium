package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MarketManager {

    private final AurelEconomy plugin;
    private final Set<Material> blacklist = ConcurrentHashMap.newKeySet();
    private final Map<String, MarketEntry> entryCache = new ConcurrentHashMap<>();
    private final Map<String, Double> buyPrices = new ConcurrentHashMap<>(); // Key: Material name OR Custom Name
    private final Map<String, Double> sellPrices = new ConcurrentHashMap<>();
    private final Set<String> alertedItems = ConcurrentHashMap.newKeySet();
    private double priceIncreaseRate;
    private double priceDecreaseRate;
    private boolean dynamicPricing;
    private boolean alertsEnabled;
    private double alertMinBasePrice;
    private double alertThresholdPercent;
    private boolean configModified = false;
    // Stabilization
    private double priceFloorPercent;
    private double priceCeilingPercent;
    private boolean recoveryEnabled;
    private double recoveryRate;

    public MarketManager(AurelEconomy plugin) {
        this.plugin = plugin;
        loadBlacklist();
        cacheAlertSettings();
        initializePrices();
    }

    private void cacheAlertSettings() {
        dynamicPricing = plugin.getConfig().getBoolean("market.dynamic-pricing", true);
        alertsEnabled = plugin.getConfig().getBoolean("market.alerts.enabled", true);
        alertMinBasePrice = plugin.getConfig().getDouble("market.alerts.min-base-price", 200.0);
        alertThresholdPercent = plugin.getConfig().getDouble("market.alerts.threshold", 0.5);
        // Stabilization
        priceFloorPercent = plugin.getConfig().getDouble("market.price-floor", 0.2);
        priceCeilingPercent = plugin.getConfig().getDouble("market.price-ceiling", 5.0);
        recoveryEnabled = plugin.getConfig().getBoolean("market.price-recovery.enabled", true);
        recoveryRate = plugin.getConfig().getDouble("market.price-recovery.rate", 0.01);
        int recoveryInterval = plugin.getConfig().getInt("market.price-recovery.interval-minutes", 10);

        // Schedule passive price recovery
        if (recoveryEnabled && dynamicPricing) {
            long ticks = recoveryInterval * 60L * 20L; // minutes -> ticks
            plugin.getServer().getScheduler().runTaskTimer(plugin, this::recoverPrices, ticks, ticks);
        }
    }

    private void loadBlacklist() {
        blacklist.clear();
        for (String materialName : plugin.getConfig().getStringList("market.blacklist")) {
            try {
                blacklist.add(Material.valueOf(materialName.toUpperCase()));
            } catch (IllegalArgumentException e) {
                plugin.getComponentLogger().warn("Invalid material in blacklist: " + materialName);
            }
        }
    }

    private void initializePrices() {
        FileConfiguration config = plugin.getConfig();
        boolean saveNeeded = false;

        // Cache rates
        priceIncreaseRate = config.getDouble("market.price-increase-per-buy", 0.001);
        priceDecreaseRate = config.getDouble("market.price-decrease-per-sell", 0.001);

        // Iterate through all MarketItems and load/save prices
        for (Category cat : Category.values()) {
            for (MarketEntry entry : MarketItems.getItems(cat)) {

                // Determine the Key for this item
                String key = entry.material.name();
                if (entry.customName != null && entry.material == Material.SPAWNER) {
                    key = entry.customName;
                }

                // Add to entry cache for O(1) lookups
                entryCache.put(key, entry);

                // Config path (replace spaces with underscores for clean YAML)
                String basePath = "market.items." + key.replace(" ", "_").toUpperCase();

                // Buy Price
                if (!config.contains(basePath + ".buy")
                        || (config.getDouble(basePath + ".buy") == 1.0 && entry.price > 1.0)) {
                    config.set(basePath + ".buy", entry.price);
                    saveNeeded = true;
                }
                // Sell Price (Default 0.0 effectively disabling sell unless configured, OR
                // default 80%?
                // User asked to "remove selling items" previously, so defaulting sell to 0.0 is
                // safer
                // BUT user wants to "choose" now.
                // Let's default sell to 0.0 to respect the "Buy Only" previous state,
                // expecting owner to enable it if they want.
                // Actually, standard is usually 80% if we want to enable it easily.
                // But the previous request was strict "remove selling".
                // So default sell to 0.0.
                if (!config.contains(basePath + ".sell")) {
                    double sellPrice;
                    if (entry.customSellPrice != null) {
                        sellPrice = entry.customSellPrice;
                    } else {
                        // Default selling to configured ratio of buy price
                        double sellRatio = plugin.getConfig().getDouble("market.default-sell-ratio", 0.5);
                        sellPrice = entry.price * sellRatio;
                    }
                    config.set(basePath + ".sell", sellPrice);
                    saveNeeded = true;
                }

                buyPrices.put(key, config.getDouble(basePath + ".buy"));
                sellPrices.put(key, config.getDouble(basePath + ".sell"));
            }
        }

        if (saveNeeded) {
            plugin.saveConfig();
        }
    }

    public void onTransaction(String materialName, boolean isBuy, int amount) {
        if (!dynamicPricing) {
            return;
        }

        // Only manipulate the buy price. Sell price is mathematically bound to it.
        double currentBuy = getBuyPrice(materialName);

        if (isBuy) {
            // Demand up -> Price up
            currentBuy *= Math.pow(1 + priceIncreaseRate, amount);
        } else {
            // Supply up -> Price down
            currentBuy *= Math.pow(1 - priceDecreaseRate, amount);
        }

        // Enforce floor & ceiling relative to original base price
        MarketEntry entry = entryCache.get(materialName);
        if (entry != null) {
            double floor = entry.price * priceFloorPercent;
            double ceiling = entry.price * priceCeilingPercent;
            currentBuy = Math.max(currentBuy, floor);
            currentBuy = Math.min(currentBuy, ceiling);
        }

        // Save new buy price to memory
        buyPrices.put(materialName, currentBuy);

        // Check for alerts
        checkPriceAlert(materialName, currentBuy);

        // Update config in memory, but don't save to disk yet (batching optimization)
        String basePath = "market.items." + materialName.replace(" ", "_").toUpperCase();
        plugin.getConfig().set(basePath + ".buy", currentBuy);
        configModified = true;
    }

    private void checkPriceAlert(String key, double newPrice) {
        if (!alertsEnabled) {
            return;
        }

        MarketEntry entry = entryCache.get(key);
        if (entry == null)
            return;

        if (entry.price < alertMinBasePrice) {
            return;
        }

        double alertThreshold = entry.price * alertThresholdPercent;
        double resetThreshold = entry.price * (alertThresholdPercent + 0.1);

        if (newPrice <= alertThreshold && !alertedItems.contains(key)) {
            // Broadcast crash
            String displayName = entry.customName != null ? entry.customName : entry.material.name().replace("_", " ");
            net.kyori.adventure.text.Component message = net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.text("[Market] ",
                            net.kyori.adventure.text.format.NamedTextColor.GOLD))
                    .append(net.kyori.adventure.text.Component.text("📉 CRASH ALERT: ",
                            net.kyori.adventure.text.format.NamedTextColor.RED))
                    .append(net.kyori.adventure.text.Component.text(displayName,
                            net.kyori.adventure.text.format.NamedTextColor.AQUA))
                    .append(net.kyori.adventure.text.Component.text(" is now at a massive discount! ",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY))
                    .append(net.kyori.adventure.text.Component.text(plugin.getEconomyManager().format(newPrice),
                            net.kyori.adventure.text.format.NamedTextColor.GREEN))
                    .build();

            plugin.getServer().broadcast(message);
            alertedItems.add(key);
        } else if (newPrice > resetThreshold && alertedItems.contains(key)) {
            // Reset alert for next time
            alertedItems.remove(key);
        }
    }

    public void persistPrices() {
        if (configModified) {
            plugin.saveConfig();
            configModified = false;
        }
    }

    /**
     * Passive price recovery: nudge all prices back toward their original base.
     * Called on a repeating timer.
     */
    private void recoverPrices() {
        for (Map.Entry<String, MarketEntry> e : entryCache.entrySet()) {
            String key = e.getKey();
            double basePrice = e.getValue().price;
            double currentPrice = getBuyPrice(key);

            if (currentPrice == basePrice)
                continue;

            // Move current price toward base by recoveryRate % of the gap
            double newPrice = currentPrice + (basePrice - currentPrice) * recoveryRate;

            // Snap to base if close enough (avoid floating-point drift)
            if (Math.abs(newPrice - basePrice) < 0.01) {
                newPrice = basePrice;
            }

            buyPrices.put(key, newPrice);
            String basePath = "market.items." + key.replace(" ", "_").toUpperCase();
            plugin.getConfig().set(basePath + ".buy", newPrice);
            configModified = true;
        }
    }

    public boolean isBlacklisted(Material material) {
        return blacklist.contains(material);
    }

    /**
     * Get buy price for a standard material.
     */
    public double getBuyPrice(Material material) {
        return getBuyPrice(material.name());
    }

    /**
     * Get buy price for a specific key (Material name or Custom Name).
     */
    public double getBuyPrice(String key) {
        return buyPrices.getOrDefault(key, 0.0);
    }

    public double getSellPrice(Material material) {
        return getSellPrice(material.name());
    }

    public double getSellPrice(String key) {
        // If it was explicitly disabled/set to 0 in config by admin, honor it
        // immediately
        double configSell = sellPrices.getOrDefault(key, 0.0);
        if (configSell <= 0.0)
            return 0.0;

        MarketEntry entry = entryCache.get(key);
        if (entry == null)
            return configSell;

        double baseBuyPrice = entry.price;
        if (baseBuyPrice <= 0)
            return 0.0;

        double currentBuyPrice = getBuyPrice(key);
        double multiplier = currentBuyPrice / baseBuyPrice;

        // Apply exactly the same stock market multiplier to the base sell price
        double dynamicSellPrice = configSell * multiplier;

        // Anti-exploit safeguard: dynamic sell can never exceed dynamic buy
        if (dynamicSellPrice >= currentBuyPrice) {
            dynamicSellPrice = currentBuyPrice * 0.99; // Cap it just underneath buy price
        }

        return dynamicSellPrice;
    }

    public java.util.List<MarketEntry> getItemsByCategory(String categoryName) {
        for (Category cat : Category.values()) {
            if (cat.name.equalsIgnoreCase(categoryName) || cat.name().equalsIgnoreCase(categoryName)) {
                return com.aureleconomy.market.MarketItems.getItems(cat);
            }
        }
        return new java.util.ArrayList<>();
    }

    public java.util.List<MarketEntry> getOrderItemsByCategory(String categoryName) {
        for (Category cat : Category.values()) {
            if (cat.name.equalsIgnoreCase(categoryName) || cat.name().equalsIgnoreCase(categoryName)) {
                return com.aureleconomy.market.MarketItems.getOrderItems(cat);
            }
        }
        return new java.util.ArrayList<>();
    }
}

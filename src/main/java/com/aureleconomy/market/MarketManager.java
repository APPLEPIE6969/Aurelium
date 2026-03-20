package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MarketManager {

    private final AurelEconomy plugin;
    private final Set<Material> blacklist = ConcurrentHashMap.newKeySet();
    private final Map<String, MarketEntry> entryCache = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> buyPrices = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> sellPrices = new ConcurrentHashMap<>();
    private final Map<String, String> itemCurrencies = new ConcurrentHashMap<>();
    private final Set<String> alertedItems = ConcurrentHashMap.newKeySet();
    private BigDecimal priceIncreaseRate;
    private BigDecimal priceDecreaseRate;
    private boolean dynamicPricing;
    private boolean alertsEnabled;
    private BigDecimal alertMinBasePrice;
    private BigDecimal alertThresholdPercent;
    private boolean configModified = false;
    private BigDecimal priceFloorPercent;
    private BigDecimal priceCeilingPercent;
    private boolean recoveryEnabled;
    private BigDecimal recoveryRate;

    public MarketManager(AurelEconomy plugin) {
        this.plugin = plugin;
        loadBlacklist();
        cacheAlertSettings();
        initializePrices();
    }

    private void cacheAlertSettings() {
        dynamicPricing = plugin.getConfig().getBoolean("market.dynamic-pricing", true);
        alertsEnabled = plugin.getConfig().getBoolean("market.alerts.enabled", true);
        alertMinBasePrice = BigDecimal.valueOf(plugin.getConfig().getDouble("market.alerts.min-base-price", 200.0));
        alertThresholdPercent = BigDecimal.valueOf(plugin.getConfig().getDouble("market.alerts.threshold", 0.5));
        priceFloorPercent = BigDecimal.valueOf(plugin.getConfig().getDouble("market.price-floor", 0.2));
        priceCeilingPercent = BigDecimal.valueOf(plugin.getConfig().getDouble("market.price-ceiling", 5.0));
        recoveryEnabled = plugin.getConfig().getBoolean("market.price-recovery.enabled", true);
        recoveryRate = BigDecimal.valueOf(plugin.getConfig().getDouble("market.price-recovery.rate", 0.01));
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

        priceIncreaseRate = BigDecimal.valueOf(config.getDouble("market.price-increase-per-buy", 0.001));
        priceDecreaseRate = BigDecimal.valueOf(config.getDouble("market.price-decrease-per-sell", 0.001));

        for (Category cat : Category.values()) {
            if (cat == Category.ALL_ITEMS) continue;
            for (MarketEntry entry : MarketItems.getItems(cat)) {
                processEntry(entry, config);
            }
        }
        for (MarketEntry entry : MarketItems.getItems(Category.ALL_ITEMS)) {
            String key = (entry.customName != null) ? entry.material.name() + "_" + entry.customName.replace(" ", "_").toUpperCase()
                    : entry.material.name();
            if (!entryCache.containsKey(key)) {
                processEntry(entry, config);
            }
        }

        if (configModified) {
            plugin.saveConfig();
            configModified = false;
        }
    }

    private void processEntry(MarketEntry entry, FileConfiguration config) {
        String key = entry.material.name();
        if (entry.customName != null) {
            key = entry.material.name() + "_" + entry.customName.replace(" ", "_").toUpperCase();
        }

        entryCache.put(key, entry);

        String basePath = "market-items." + key.replace(" ", "_").toUpperCase();

        BigDecimal configBuy = config.contains(basePath + ".buy") ? BigDecimal.valueOf(config.getDouble(basePath + ".buy")) : BigDecimal.ZERO;
        if (!config.contains(basePath + ".buy")
                || (configBuy.compareTo(BigDecimal.ONE) == 0 && entry.price.compareTo(BigDecimal.ONE) > 0)) {
            config.set(basePath + ".buy", entry.price.doubleValue());
            configModified = true;
            configBuy = entry.price;
        }

        BigDecimal configSell;
        if (!config.contains(basePath + ".sell")) {
            if (entry.customSellPrice != null) {
                configSell = entry.customSellPrice;
            } else {
                BigDecimal sellRatio = BigDecimal.valueOf(plugin.getConfig().getDouble("market.default-sell-ratio", 0.5));
                configSell = entry.price.multiply(sellRatio);
            }
            config.set(basePath + ".sell", configSell.doubleValue());
            configModified = true;
        } else {
            configSell = BigDecimal.valueOf(config.getDouble(basePath + ".sell"));
        }

        buyPrices.put(key, configBuy);
        sellPrices.put(key, configSell);

        String curPath = basePath + ".currency";
        if (!config.contains(curPath)) {
            config.set(curPath, plugin.getEconomyManager().getDefaultCurrency());
            configModified = true;
        }
        itemCurrencies.put(key, config.getString(curPath, plugin.getEconomyManager().getDefaultCurrency()));
    }

    public void onTransaction(String materialName, boolean isBuy, int amount) {
        if (!dynamicPricing) {
            return;
        }

        buyPrices.compute(materialName, (key, currentBuy) -> {
            if (currentBuy == null) currentBuy = BigDecimal.ZERO;
            
            BigDecimal updatedPrice;
            if (isBuy) {
                // Demand up -> Price up
                double multiplier = Math.pow(1 + priceIncreaseRate.doubleValue(), amount);
                updatedPrice = currentBuy.multiply(BigDecimal.valueOf(multiplier));
            } else {
                // Supply up -> Price down
                double multiplier = Math.pow(1 - priceDecreaseRate.doubleValue(), amount);
                updatedPrice = currentBuy.multiply(BigDecimal.valueOf(multiplier));
            }

            // Clamp to floor/ceiling
            MarketEntry entry = entryCache.get(materialName);
            if (entry != null) {
                BigDecimal floor = entry.price.multiply(priceFloorPercent);
                BigDecimal ceiling = entry.price.multiply(priceCeilingPercent);
                if (updatedPrice.compareTo(floor) < 0) updatedPrice = floor;
                if (updatedPrice.compareTo(ceiling) > 0) updatedPrice = ceiling;
            }

            String basePath = "market-items." + materialName.replace(" ", "_").toUpperCase();
            plugin.getConfig().set(basePath + ".buy", updatedPrice.doubleValue());
            configModified = true;

            // Check for alerts
            checkPriceAlert(materialName, updatedPrice);

            return updatedPrice;
        });
    }

    private void checkPriceAlert(String key, BigDecimal newPrice) {
        if (!alertsEnabled) {
            return;
        }

        MarketEntry entry = entryCache.get(key);
        if (entry == null)
            return;

        if (entry.price.compareTo(alertMinBasePrice) < 0) {
            return;
        }

        BigDecimal alertThreshold = entry.price.multiply(alertThresholdPercent);
        BigDecimal resetThreshold = entry.price.multiply(alertThresholdPercent.add(BigDecimal.valueOf(0.1)));

        if (newPrice.compareTo(alertThreshold) <= 0 && !alertedItems.contains(key)) {
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
                    .append(net.kyori.adventure.text.Component.text(plugin.getEconomyManager().getFormattedWithSymbol(newPrice, plugin.getEconomyManager().getDefaultCurrency()),
                            net.kyori.adventure.text.format.NamedTextColor.GREEN))
                    .build();

            plugin.getServer().broadcast(message);
            alertedItems.add(key);
        } else if (newPrice.compareTo(resetThreshold) > 0 && alertedItems.contains(key)) {
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

    private void recoverPrices() {
        for (Map.Entry<String, MarketEntry> e : entryCache.entrySet()) {
            String key = e.getKey();
            BigDecimal basePrice = e.getValue().price;
            BigDecimal currentPrice = getBuyPrice(key);

            if (currentPrice.compareTo(basePrice) == 0)
                continue;

            BigDecimal gap = basePrice.subtract(currentPrice);
            BigDecimal adjustment = gap.multiply(recoveryRate);
            BigDecimal newPrice = currentPrice.add(adjustment);

            // Snap to base if close enough
            if (gap.abs().compareTo(BigDecimal.valueOf(0.01)) < 0) {
                newPrice = basePrice;
            }

            buyPrices.put(key, newPrice);
            String basePath = "market-items." + key.replace(" ", "_").toUpperCase();
            plugin.getConfig().set(basePath + ".buy", newPrice.doubleValue());
            configModified = true;
        }
    }

    public Map<String, MarketEntry> getEntryCache() {
        return entryCache;
    }

    public boolean isBlacklisted(Material material) {
        return blacklist.contains(material);
    }

    public BigDecimal getBuyPrice(Material material) {
        return getBuyPrice(material.name());
    }

    public BigDecimal getBuyPrice(String key) {
        return buyPrices.getOrDefault(key, BigDecimal.ZERO);
    }

    public BigDecimal getSellPrice(Material material) {
        return getSellPrice(material.name());
    }

    public BigDecimal getSellPrice(String key) {
        BigDecimal configSell = sellPrices.getOrDefault(key, BigDecimal.ZERO);
        if (configSell.compareTo(BigDecimal.ZERO) <= 0)
            return BigDecimal.ZERO;

        MarketEntry entry = entryCache.get(key);
        if (entry == null)
            return configSell;

        BigDecimal baseBuyPrice = entry.price;
        if (baseBuyPrice.compareTo(BigDecimal.ZERO) <= 0)
            return BigDecimal.ZERO;

        BigDecimal currentBuyPrice = getBuyPrice(key);

        // Calculate the ratio saved in config
        BigDecimal ratio = configSell.divide(baseBuyPrice, 4, RoundingMode.HALF_UP);

        if (ratio.compareTo(BigDecimal.valueOf(0.95)) > 0) {
            ratio = BigDecimal.valueOf(plugin.getConfig().getDouble("market.default-sell-ratio", 0.5));
        }

        BigDecimal dynamicSellPrice = currentBuyPrice.multiply(ratio);

        // Sell can never exceed buy
        if (dynamicSellPrice.compareTo(currentBuyPrice) >= 0) {
            dynamicSellPrice = currentBuyPrice.multiply(BigDecimal.valueOf(0.99));
        }

        return dynamicSellPrice;
    }

    public String getCurrency(String key) {
        return itemCurrencies.getOrDefault(key, plugin.getEconomyManager().getDefaultCurrency());
    }

    public String getCurrency(Material material) {
        return getCurrency(material.name());
    }

    public java.util.List<MarketEntry> getItemsByCategory(String categoryName) {
        for (Category cat : Category.values()) {
            if (cat.name.equalsIgnoreCase(categoryName) || cat.name().equalsIgnoreCase(categoryName)) {
                return MarketItems.getItems(cat);
            }
        }
        return new java.util.ArrayList<>();
    }

    public java.util.List<MarketEntry> getOrderItemsByCategory(String categoryName) {
        for (Category cat : Category.values()) {
            if (cat.name.equalsIgnoreCase(categoryName) || cat.name().equalsIgnoreCase(categoryName)) {
                return MarketItems.getOrderItems(cat);
            }
        }
        return new java.util.ArrayList<>();
    }
}

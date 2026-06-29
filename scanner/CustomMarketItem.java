package com.aureleconomy.scanner;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;

/**
 * Data model representing a discovered custom item, ready for registration
 * in the market and auction systems. Uses a Builder pattern consistent with
 * AuctionItem's builder style.
 */
public class CustomMarketItem {

    private final String canonicalId;
    private final ItemStack itemStack;
    private final String sourcePlugin;
    private final String displayName;
    private final String pdcKey;
    private final String modelDataKey;
    private final String loreHash;
    private final String pluginNativeId;
    private final String category;
    private final BigDecimal buyPrice;
    private final BigDecimal sellPrice;
    private final boolean enabled;

    private CustomMarketItem(Builder builder) {
        this.canonicalId = builder.canonicalId;
        this.itemStack = builder.itemStack;
        this.sourcePlugin = builder.sourcePlugin;
        this.displayName = builder.displayName;
        this.pdcKey = builder.pdcKey;
        this.modelDataKey = builder.modelDataKey;
        this.loreHash = builder.loreHash;
        this.pluginNativeId = builder.pluginNativeId;
        this.category = builder.category;
        this.buyPrice = builder.buyPrice;
        this.sellPrice = builder.sellPrice;
        this.enabled = builder.enabled;
    }

    public String getCanonicalId() { return canonicalId; }
    public ItemStack getItemStack() { return itemStack; }
    public String getSourcePlugin() { return sourcePlugin; }
    public String getDisplayName() { return displayName; }
    public String getPdcKey() { return pdcKey; }
    public String getModelDataKey() { return modelDataKey; }
    public String getLoreHash() { return loreHash; }
    public String getPluginNativeId() { return pluginNativeId; }
    public String getCategory() { return category; }
    public BigDecimal getBuyPrice() { return buyPrice; }
    public BigDecimal getSellPrice() { return sellPrice; }
    public boolean isEnabled() { return enabled; }

    public static class Builder {
        private String canonicalId;
        private ItemStack itemStack;
        private String sourcePlugin = "Unknown";
        private String displayName = "";
        private String pdcKey;
        private String modelDataKey;
        private String loreHash;
        private String pluginNativeId;
        private String category = "CUSTOM_ITEMS";
        private BigDecimal buyPrice = BigDecimal.valueOf(-1);
        private BigDecimal sellPrice = BigDecimal.valueOf(-1);
        private boolean enabled = true;

        public Builder canonicalId(String canonicalId) { this.canonicalId = canonicalId; return this; }
        public Builder itemStack(ItemStack itemStack) { this.itemStack = itemStack != null ? itemStack.clone() : null; return this; }
        public Builder sourcePlugin(String sourcePlugin) { this.sourcePlugin = sourcePlugin; return this; }
        public Builder displayName(String displayName) { this.displayName = displayName; return this; }
        public Builder pdcKey(String pdcKey) { this.pdcKey = pdcKey; return this; }
        public Builder modelDataKey(String modelDataKey) { this.modelDataKey = modelDataKey; return this; }
        public Builder loreHash(String loreHash) { this.loreHash = loreHash; return this; }
        public Builder pluginNativeId(String pluginNativeId) { this.pluginNativeId = pluginNativeId; return this; }
        public Builder category(String category) { this.category = category; return this; }
        public Builder buyPrice(BigDecimal buyPrice) { this.buyPrice = buyPrice; return this; }
        public Builder sellPrice(BigDecimal sellPrice) { this.sellPrice = sellPrice; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }

        public CustomMarketItem build() {
            if (canonicalId == null || canonicalId.isEmpty()) {
                throw new IllegalStateException("canonicalId is required");
            }
            if (itemStack == null) {
                throw new IllegalStateException("itemStack is required");
            }
            return new CustomMarketItem(this);
        }
    }
}

package com.aureleconomy.scanner;

import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;

/**
 * Data model representing a discovered custom item, ready for registration
 * in the market and auction systems. Uses a Builder pattern consistent with
 * AuctionItem's builder style.
 *
 * Price sentinel: buyPrice/sellPrice of -1 means "unset / use default".
 * All other negative values are rejected by the builder.
 */
public class CustomMarketItem {

 /** Sentinel value indicating the price has not been explicitly set. */
 public static final BigDecimal PRICE_UNSET = BigDecimal.valueOf(-1);

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
 this.pluginNativeId = builder.pluginNativeId;
 this.pdcKey = builder.pdcKey;
 this.modelDataKey = builder.modelDataKey;
 this.loreHash = builder.loreHash;
 this.category = builder.category;
 this.buyPrice = builder.buyPrice;
 this.sellPrice = builder.sellPrice;
 this.enabled = builder.enabled;
 }

 public String getCanonicalId() { return canonicalId; }
 public ItemStack getItemStack() { return itemStack != null ? itemStack.clone() : null; }
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

 /**
 * Returns whether this item's buy price has been explicitly set
 * (i.e. is not the PRICE_UNSET sentinel).
 */
 public boolean hasBuyPrice() { return buyPrice.compareTo(PRICE_UNSET) != 0; }

 /**
 * Returns whether this item's sell price has been explicitly set
 * (i.e. is not the PRICE_UNSET sentinel).
 */
 public boolean hasSellPrice() { return sellPrice.compareTo(PRICE_UNSET) != 0; }

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
 private BigDecimal buyPrice = PRICE_UNSET;
 private BigDecimal sellPrice = PRICE_UNSET;
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

 /**
 * Set the buy price. Use -1 (PRICE_UNSET) to indicate "not set / use default".
 * Any other negative value is rejected.
 */
 public Builder buyPrice(BigDecimal buyPrice) {
 if (buyPrice != null && buyPrice.compareTo(PRICE_UNSET) != 0 && buyPrice.compareTo(BigDecimal.ZERO) < 0) {
 throw new IllegalArgumentException("buyPrice must be >= 0 or PRICE_UNSET (-1), got: " + buyPrice);
 }
 this.buyPrice = buyPrice;
 return this;
 }

 /**
 * Set the sell price. Use -1 (PRICE_UNSET) to indicate "not set / use default".
 * Any other negative value is rejected.
 */
 public Builder sellPrice(BigDecimal sellPrice) {
 if (sellPrice != null && sellPrice.compareTo(PRICE_UNSET) != 0 && sellPrice.compareTo(BigDecimal.ZERO) < 0) {
 throw new IllegalArgumentException("sellPrice must be >= 0 or PRICE_UNSET (-1), got: " + sellPrice);
 }
 this.sellPrice = sellPrice;
 return this;
 }

 public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }

 public CustomMarketItem build() {
 if (canonicalId == null || canonicalId.isEmpty()) {
 throw new IllegalStateException("canonicalId is required");
 }
 if (canonicalId.length() > 128) {
 throw new IllegalStateException("canonicalId too long (max 128 chars): " + canonicalId.length());
 }
 if (canonicalId.contains(" ") || canonicalId.contains("\t") || canonicalId.contains("\n")) {
 throw new IllegalStateException("canonicalId must not contain whitespace: '" + canonicalId + "'");
 }
 if (itemStack == null) {
 throw new IllegalStateException("itemStack is required");
 }
 // Default empty displayName to material name if not set
 if (displayName == null || displayName.isEmpty()) {
 this.displayName = itemStack.getType().name();
 }
 return new CustomMarketItem(this);
 }
 }
}

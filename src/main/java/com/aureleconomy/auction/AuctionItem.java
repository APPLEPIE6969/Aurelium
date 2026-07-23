package com.aureleconomy.auction;

import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class AuctionItem {
 public enum PurchaseMode {
 STACK,   // Buyer purchases the entire stack at once (original behavior)
 UNIT     // Buyer can purchase individual units from the stack
 }

 private final int id;
 private final UUID seller;
 private final ItemStack item;
 private BigDecimal price;
 private final String currency;
 private final boolean isBin;
 private final long expiration;
 private final BigDecimal listingFee;
 private final long startTime;
 private final PurchaseMode purchaseMode;
 private UUID highestBidder;
 private boolean ended;
 private boolean collected;

 private AuctionItem(Builder builder) {
 this.id = builder.id;
 this.seller = builder.seller;
 this.item = builder.item;
 this.price = builder.price;
 this.currency = builder.currency;
 this.isBin = builder.isBin;
 this.expiration = builder.expiration;
 this.listingFee = builder.listingFee;
 this.startTime = builder.startTime;
 this.purchaseMode = builder.purchaseMode;
 this.highestBidder = builder.highestBidder;
 this.ended = builder.ended;
 this.collected = builder.collected;
 }

 public int getId() {
 return id;
 }

 public UUID getSeller() {
 return seller;
 }

 public ItemStack getItem() {
 return item;
 }

 public BigDecimal getPrice() {
 return price;
 }

 public String getCurrency() {
 return currency;
 }

 public boolean isBin() {
 return isBin;
 }

 public long getExpiration() {
 return expiration;
 }

 public BigDecimal getListingFee() {
 return listingFee;
 }

 public long getStartTime() {
 return startTime;
 }

 public PurchaseMode getPurchaseMode() {
 return purchaseMode;
 }

 public UUID getHighestBidder() {
 return highestBidder;
 }

 public boolean isEnded() {
 return ended;
 }

 public boolean isCollected() {
 return collected;
 }

 public void setPrice(BigDecimal price) {
 this.price = price;
 }

 public void setHighestBidder(UUID highestBidder) {
 this.highestBidder = highestBidder;
 }

 public void setEnded(boolean ended) {
 this.ended = ended;
 }

 public void setCollected(boolean collected) {
 this.collected = collected;
 }

 /**
 * Returns the price per unit (always current price, regardless of purchase mode).
 * In STACK mode this is the full-stack price. In UNIT mode this is per-item.
 */
 public BigDecimal getPricePerUnit() {
 return price;
 }

 /**
 * In UNIT mode, returns the current quantity available. In STACK mode returns item amount.
 */
 public int getAvailableQuantity() {
 return item.getAmount();
 }

 public static class Builder {
 private int id;
 private UUID seller;
 private ItemStack item;
 private BigDecimal price = BigDecimal.ZERO;
 private String currency = null;
 private boolean isBin;
 private long expiration;
 private BigDecimal listingFee = BigDecimal.ZERO;
 private long startTime;
 private PurchaseMode purchaseMode = PurchaseMode.STACK;
 private UUID highestBidder;
 private boolean ended;
 private boolean collected;

 public Builder id(int id) {
 this.id = id;
 return this;
 }

 public Builder seller(UUID seller) {
 this.seller = seller;
 return this;
 }

 public Builder item(ItemStack item) {
 this.item = item;
 return this;
 }

 public Builder price(BigDecimal price) {
 this.price = price;
 return this;
 }

 public Builder currency(String currency) {
 this.currency = currency;
 return this;
 }

 public Builder isBin(boolean isBin) {
 this.isBin = isBin;
 return this;
 }

 public Builder expiration(long expiration) {
 this.expiration = expiration;
 return this;
 }

 public Builder listingFee(BigDecimal listingFee) {
 this.listingFee = listingFee;
 return this;
 }

 public Builder startTime(long startTime) {
 this.startTime = startTime;
 return this;
 }

 public Builder purchaseMode(PurchaseMode purchaseMode) {
 this.purchaseMode = purchaseMode;
 return this;
 }

 public Builder highestBidder(UUID highestBidder) {
 this.highestBidder = highestBidder;
 return this;
 }

 public Builder ended(boolean ended) {
 this.ended = ended;
 return this;
 }

 public Builder collected(boolean collected) {
 this.collected = collected;
 return this;
 }

 public AuctionItem build() {
 if (seller == null || item == null) {
 throw new IllegalStateException("Seller and Item must be provided for an AuctionItem.");
 }
 return new AuctionItem(this);
 }
 }
}

package com.aureleconomy.auction;

import java.util.UUID;
import org.bukkit.inventory.ItemStack;

public class AuctionItem {
    private final int id;
    private final UUID seller;
    private final ItemStack item;
    private double price;
    private final boolean isBin;
    private final long expiration;
    private final double listingFee;
    private final long startTime;
    private UUID highestBidder;
    private boolean ended;
    private boolean collected;

    public AuctionItem(int id, UUID seller, ItemStack item, double price, boolean isBin, long expiration,
            double listingFee, long startTime, UUID highestBidder, boolean ended, boolean collected) {
        this.id = id;
        this.seller = seller;
        this.item = item;
        this.price = price;
        this.isBin = isBin;
        this.expiration = expiration;
        this.listingFee = listingFee;
        this.startTime = startTime;
        this.highestBidder = highestBidder;
        this.ended = ended;
        this.collected = collected;
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

    public double getPrice() {
        return price;
    }

    public boolean isBin() {
        return isBin;
    }

    public long getExpiration() {
        return expiration;
    }

    public double getListingFee() {
        return listingFee;
    }

    public long getStartTime() {
        return startTime;
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

    public void setPrice(double price) {
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
}

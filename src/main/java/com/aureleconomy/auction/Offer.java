package com.aureleconomy.auction;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Represents an offer made by a bidder on an auction item.
 * Refactored to use BigDecimal for currency precision and OfferStatus enum for type safety.
 */
public class Offer {
    private final int id;
    private final int auctionId;
    private final UUID bidder;
    private final BigDecimal amount;
    private OfferStatus status;
    private final long timestamp;

    public Offer(int id, int auctionId, UUID bidder, BigDecimal amount, OfferStatus status, long timestamp) {
        this.id = id;
        this.auctionId = auctionId;
        this.bidder = bidder;
        this.amount = amount;
        this.status = status;
        this.timestamp = timestamp;
    }

    public int getId() {
        return id;
    }

    public int getAuctionId() {
        return auctionId;
    }

    public UUID getBidder() {
        return bidder;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public void setStatus(OfferStatus status) {
        this.status = status;
    }

    public long getTimestamp() {
        return timestamp;
    }
}

package com.aureleconomy.auction;

import java.util.UUID;

public class Offer {
    private final int id;
    private final int auctionId;
    private final UUID bidder;
    private final double amount;
    private String status;
    private final long timestamp;

    public Offer(int id, int auctionId, UUID bidder, double amount, String status, long timestamp) {
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

    public double getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getTimestamp() {
        return timestamp;
    }
}

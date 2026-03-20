package com.aureleconomy.auction;

/**
 * Represents the various states an offer can be in.
 */
public enum OfferStatus {
    /**
     * The offer has been placed but not yet acted upon by the seller.
     */
    PENDING,

    /**
     * The seller has accepted the offer.
     */
    ACCEPTED,

    /**
     * The seller has explicitly rejected the offer.
     */
    REJECTED,

    /**
     * The offer was not accepted before the auction ended or the bidder cancelled.
     */
    EXPIRED,

    /**
     * Automatically cancelled due to external factors (e.g., bidder ran out of funds).
     */
    CANCELLED
}

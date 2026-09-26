package com.aureleconomy.webstore;

import java.util.List;
import java.util.UUID;

/**
 * Durable queue of browser-submitted actions waiting to run in-game.
 *
 * <p>The dashboard POSTs a purchase and immediately starts polling its status,
 * exactly as it does against the standalone backend, so the request thread only
 * ever enqueues. Execution happens on the main thread via
 * {@code WebPurchaseExecutor}, which is what keeps Bukkit API calls off an HTTP
 * worker.
 *
 * <p>This is the only part of the dashboard that is worth persisting. A purchase
 * is marked completed only after the player has been charged and given the
 * item, so a lost pending entry is a failed click rather than lost money — but
 * the player still deserves not to have their click evaporate, which is what
 * {@link SqlitePurchaseQueue} is for.
 */
public interface PurchaseQueue {

    enum Type {
        /** Buy {@code amount} of {@code itemKey} from the server market. */
        BUY,
        /** Place a bid on {@code auctionId}. */
        BID,
        /** Fulfill buy order {@code orderId} with {@code amount} items. */
        FILL_ORDER
    }

    /** One queued action. */
    final class Purchase {
        private final String id;
        private final Type type;
        private final UUID player;
        private final String itemKey;
        private final int auctionId;
        private final int orderId;
        private final int amount;
        private final long createdAt;
        private volatile String status;
        private volatile String resultJson;

        Purchase(String id, Type type, UUID player, String itemKey, int auctionId,
                 int orderId, int amount, long createdAt, String status, String resultJson) {
            this.id = id;
            this.type = type;
            this.player = player;
            this.itemKey = itemKey;
            this.auctionId = auctionId;
            this.orderId = orderId;
            this.amount = amount;
            this.createdAt = createdAt;
            this.status = status;
            this.resultJson = resultJson;
        }

        public String id() {
            return id;
        }

        public Type type() {
            return type;
        }

        public UUID player() {
            return player;
        }

        public String itemKey() {
            return itemKey;
        }

        public int auctionId() {
            return auctionId;
        }

        public int orderId() {
            return orderId;
        }

        public int amount() {
            return amount;
        }

        public long createdAt() {
            return createdAt;
        }

        public String status() {
            return status;
        }

        void status(String status) {
            this.status = status;
        }

        public String resultJson() {
            return resultJson;
        }

        void resultJson(String resultJson) {
            this.resultJson = resultJson;
        }
    }

    String PENDING = "pending";
    String PROCESSING = "processing";
    String COMPLETED = "completed";
    String FAILED = "failed";

    /** Queue an action and return the id the browser polls with. */
    String enqueue(Type type, UUID player, String itemKey, int auctionId, int orderId, int amount);

    /**
     * Atomically claim every pending action for one player, moving them to
     * {@code processing} so a second claim cannot pick them up.
     */
    List<Purchase> claimPending(UUID player);

    Purchase find(String purchaseId);

    void complete(String purchaseId, boolean success, String resultJson);

    /** Drop settled entries older than {@code maxAgeMs}. */
    void prune(long maxAgeMs);

    void close();
}

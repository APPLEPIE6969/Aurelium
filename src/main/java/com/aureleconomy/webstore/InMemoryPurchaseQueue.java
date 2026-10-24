package com.aureleconomy.webstore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link PurchaseQueue}. The default when durability is not wanted.
 *
 * <p>Nothing is written to disk, so a restart drops anything still queued. That
 * is safe: a purchase is only marked completed after the player was charged and
 * given the item, so a dropped entry is a failed click, not lost money.
 *
 * <p>It enforces the same TTL and first-completion-wins rules as
 * {@link SqlitePurchaseQueue}, so the fallback cannot behave differently from
 * the durable queue.
 */
public class InMemoryPurchaseQueue implements PurchaseQueue {

    private static final long DEFAULT_TTL_MS = 15 * 60_000L;

    private final AtomicLong ids = new AtomicLong();
    private final Map<String, Purchase> purchases = new ConcurrentHashMap<>();
    private final long ttlMs;

    public InMemoryPurchaseQueue() {
        this(DEFAULT_TTL_MS);
    }

    public InMemoryPurchaseQueue(long ttlMs) {
        this.ttlMs = Math.max(0L, ttlMs);
    }

    @Override
    public String enqueue(Type type, UUID player, String itemKey, int auctionId, int orderId, int amount) {
        String id = "q-" + ids.incrementAndGet() + "-" + Long.toHexString(System.nanoTime());
        purchases.put(id, new Purchase(id, type, player, itemKey, auctionId, orderId, amount,
                System.currentTimeMillis(), PENDING, null));
        return id;
    }

    @Override
    public List<Purchase> claimPending(UUID player) {
        List<Purchase> claimed = new ArrayList<>();
        for (Purchase p : purchases.values()) {
            if (!PENDING.equals(p.status()) || !p.player().equals(player)) {
                continue;
            }
            synchronized (p) {
                if (PENDING.equals(p.status())) {
                    p.status(PROCESSING);
                    claimed.add(p);
                }
            }
        }
        return claimed;
    }

    @Override
    public Purchase find(String purchaseId) {
        return purchases.get(purchaseId);
    }

    @Override
    public boolean complete(String purchaseId, boolean success, String resultJson) {
        Purchase p = purchases.get(purchaseId);
        if (p == null) {
            return false;
        }
        synchronized (p) {
            if (!PROCESSING.equals(p.status())) {
                return false;
            }
            p.status(success ? COMPLETED : FAILED);
            p.resultJson(resultJson);
            return true;
        }
    }

    @Override
    public int expireStale() {
        long cutoff = System.currentTimeMillis() - ttlMs;
        int expired = 0;
        for (Purchase p : purchases.values()) {
            synchronized (p) {
                if ((PENDING.equals(p.status()) || PROCESSING.equals(p.status()))
                        && p.createdAt() < cutoff) {
                    p.status(FAILED);
                    p.resultJson(EXPIRED_RESULT);
                    expired++;
                }
            }
        }
        return expired;
    }

    @Override
    public void prune(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        purchases.entrySet().removeIf(e -> {
            Purchase p = e.getValue();
            return !PENDING.equals(p.status()) && !PROCESSING.equals(p.status())
                    && p.createdAt() < cutoff;
        });
    }

    @Override
    public void close() {
        purchases.clear();
    }
}

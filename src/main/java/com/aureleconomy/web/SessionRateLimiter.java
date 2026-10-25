package com.aureleconomy.web;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-session token bucket for the dashboard API.
 *
 * <p>The local dashboard runs on a four-thread pool and had no throttling at
 * all, so one authenticated browser loop could occupy every worker with
 * snapshot parsing. A bucket per player (the session manager issues one session
 * per player) keeps a normal dashboard, which polls a few times per second,
 * well inside the budget while a loop is refused with 429.
 *
 * <p>Package-private and clock-injectable so the burst and refill behaviour can
 * be tested without sleeping.
 */
final class SessionRateLimiter {

    /** How long an idle bucket is kept before it is swept. */
    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000L;

    private final double permitsPerSecond;
    private final double burst;
    private final LongSupplier nanoClock;
    private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicLong acquisitions = new AtomicLong();

    SessionRateLimiter(double permitsPerSecond, double burst) {
        this(permitsPerSecond, burst, System::nanoTime);
    }

    SessionRateLimiter(double permitsPerSecond, double burst, LongSupplier nanoClock) {
        this.permitsPerSecond = Math.max(1.0, permitsPerSecond);
        this.burst = Math.max(1.0, burst);
        this.nanoClock = nanoClock;
    }

    /** Takes one permit, or returns {@code false} when the bucket is empty. */
    boolean tryAcquire(UUID session) {
        long now = nanoClock.getAsLong();
        // Sweep every 256 requests rather than on a timer: it bounds the map
        // without a second scheduled task, and a request already paid for it.
        if ((acquisitions.incrementAndGet() & 0xFFL) == 0L) {
            long cutoff = now - IDLE_NANOS;
            buckets.entrySet().removeIf(entry -> entry.getValue().lastSeen < cutoff);
        }

        Bucket bucket = buckets.computeIfAbsent(session, key -> new Bucket(burst, now));
        synchronized (bucket) {
            long elapsed = now - bucket.lastRefill;
            if (elapsed > 0L) {
                bucket.tokens = Math.min(burst,
                        bucket.tokens + (elapsed / 1_000_000_000.0) * permitsPerSecond);
                bucket.lastRefill = now;
            }
            bucket.lastSeen = now;
            if (bucket.tokens < 1.0) {
                return false;
            }
            bucket.tokens -= 1.0;
            return true;
        }
    }

    private static final class Bucket {
        private double tokens;
        private long lastRefill;
        private volatile long lastSeen;

        private Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
            this.lastSeen = now;
        }
    }
}

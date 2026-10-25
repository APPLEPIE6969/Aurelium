package com.aureleconomy.web;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3: the dashboard API had no throttle at all. A bucket holds a burst of 20
 * requests, refills at 10 per second, and is per player, so one tab looping the
 * API cannot occupy the four HTTP workers.
 */
class SessionRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final SessionRateLimiter limiter = new SessionRateLimiter(10, 20, clock::get);
    private final UUID player = UUID.randomUUID();

    @Test
    @DisplayName("the burst is allowed and the next request is refused")
    void burstIsAllowedThenRefused() {
        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire(player), "request " + i + " inside the burst was refused");
        }
        assertFalse(limiter.tryAcquire(player), "the 21st request in the same instant was allowed");
    }

    @Test
    @DisplayName("permits refill at the configured rate, not all at once")
    void permitsRefillAtTheConfiguredRate() {
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire(player);
        }
        assertFalse(limiter.tryAcquire(player));

        clock.addAndGet(1_000_000_000L); // one second at 10/s
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(player), "refilled permit " + i + " was refused");
        }
        assertFalse(limiter.tryAcquire(player), "more than ten permits appeared in one second");
    }

    @Test
    @DisplayName("one player's flood does not spend another player's budget")
    void playersHaveSeparateBuckets() {
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire(player);
        }
        assertFalse(limiter.tryAcquire(player));

        assertTrue(limiter.tryAcquire(UUID.randomUUID()),
                "a second session was throttled by the first player's bucket");
    }

    @Test
    @DisplayName("a long idle period refills up to the burst, never beyond it")
    void refillIsCappedAtTheBurst() {
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire(player);
        }
        clock.addAndGet(60_000_000_000L); // a minute is 600 permits' worth

        for (int i = 0; i < 20; i++) {
            assertTrue(limiter.tryAcquire(player), "permit " + i + " after idling was refused");
        }
        assertFalse(limiter.tryAcquire(player), "the bucket refilled past its burst");
    }
}

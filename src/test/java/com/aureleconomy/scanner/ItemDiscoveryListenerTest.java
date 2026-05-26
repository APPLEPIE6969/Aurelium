package com.aureleconomy.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the rate-limiting logic used by ItemDiscoveryListener.
 * Validates that the compareAndSet-based cooldown mechanism works correctly.
 */
public class ItemDiscoveryListenerTest {

    private ConcurrentHashMap<UUID, AtomicLong> lastScan;
    private static final long SCAN_COOLDOWN_MS = 2000;

    @BeforeEach
    void setUp() {
        lastScan = new ConcurrentHashMap<>();
    }

    private boolean shouldScan(UUID playerId) {
        AtomicLong last = lastScan.computeIfAbsent(playerId, k -> new AtomicLong(0));
        long now = System.currentTimeMillis();
        long prev = last.get();
        if (now - prev < SCAN_COOLDOWN_MS) return false;
        return last.compareAndSet(prev, now);
    }

    @Test
    void firstScanAlwaysAllowed() {
        UUID player = UUID.randomUUID();
        assertTrue(shouldScan(player));
    }

    @Test
    void rapidSecondScanBlocked() {
        UUID player = UUID.randomUUID();
        assertTrue(shouldScan(player));
        assertFalse(shouldScan(player));
    }

    @Test
    void differentPlayersIndependent() {
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        assertTrue(shouldScan(player1));
        assertTrue(shouldScan(player2));
    }

    @Test
    void scanAllowedAfterCooldown() throws InterruptedException {
        UUID player = UUID.randomUUID();
        assertTrue(shouldScan(player));
        // Wait for cooldown to expire
        Thread.sleep(SCAN_COOLDOWN_MS + 100);
        assertTrue(shouldScan(player));
    }

    @Test
    void concurrentScansOnlyOneWins() throws InterruptedException {
        UUID player = UUID.randomUUID();
        // Reset to 0
        lastScan.put(player, new AtomicLong(0));

        int threads = 10;
        int[] wins = {0};
        Thread[] t = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            t[i] = new Thread(() -> {
                if (shouldScan(player)) {
                    synchronized (wins) {
                        wins[0]++;
                    }
                }
            });
            t[i].start();
        }
        for (Thread thread : t) {
            thread.join();
        }
        // Only one thread should win the CAS
        assertEquals(1, wins[0]);
    }

    @Test
    void cooldownNotYetExpired() {
        UUID player = UUID.randomUUID();
        assertTrue(shouldScan(player));
        // Immediately try again - should be blocked
        assertFalse(shouldScan(player));
        // And again
        assertFalse(shouldScan(player));
    }
}

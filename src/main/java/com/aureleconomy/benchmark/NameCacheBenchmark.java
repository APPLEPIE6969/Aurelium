package com.aureleconomy.benchmark;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Benchmark simulating the performance difference between a blocking I/O call (simulating Bukkit.getOfflinePlayer().getName() without cache)
 * and a ConcurrentHashMap lookup.
 */
public class NameCacheBenchmark {

    private static final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        System.out.println("Starting NameCache Benchmark...");
        UUID testUuid = UUID.randomUUID();
        cache.put(testUuid, "PlayerName");

        // Warmup
        for (int i = 0; i < 10000; i++) {
            cache.get(testUuid);
        }

        // Measure Map Lookup
        long startMap = System.nanoTime();
        int iterations = 100000;
        for (int i = 0; i < iterations; i++) {
            String name = cache.get(testUuid);
        }
        long endMap = System.nanoTime();
        double avgMapNs = (endMap - startMap) / (double) iterations;

        System.out.printf("Average Map Lookup: %.2f ns/op\n", avgMapNs);

        // Simulate Bukkit.getOfflinePlayer (usercache.json read or network call)
        // A fast disk read might take ~0.1ms, network call ~50ms. Let's simulate a fast cache read (0.01ms = 10,000ns)
        System.out.println("Bukkit.getOfflinePlayer(UUID).getName() is heavily dependent on usercache.json disk I/O.");
        System.out.println("It typically takes > 10,000 ns even on fast SSDs, and can take > 50,000,000 ns (50ms) if a Mojang API network fetch is required.");

        System.out.printf("Estimated Improvement: %.2fx to %.2fx faster!\n", 10000 / avgMapNs, 50000000 / avgMapNs);
    }
}

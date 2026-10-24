package com.aureleconomy.webstore;

import com.aureleconomy.AurelEconomy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * H10: the durable queue used to share one JDBC connection across every HTTP
 * thread, flip its autoCommit to claim, and leave a stale PENDING entry to run
 * whenever its player next logged in. These tests hold the queue to the
 * replacement contract: separate pooled connections, a claim that cannot be
 * lost or taken twice, a TTL that cancels instead of executing, and a single
 * completion per purchase.
 */
class SqlitePurchaseQueueTest {

    @TempDir
    Path tempDir;

    private SqlitePurchaseQueue queue;
    private Path databaseFile;

    @BeforeEach
    void setUp() {
        AurelEconomy plugin = mock(AurelEconomy.class);
        ComponentLogger logger = mock(ComponentLogger.class);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getComponentLogger()).thenReturn(logger);
        YamlConfiguration config = new YamlConfiguration();
        config.set("web-queue.ttl-minutes", 15);
        when(plugin.getConfig()).thenReturn(config);

        queue = new SqlitePurchaseQueue(plugin, "web-queue.db");
        queue.open();
        databaseFile = tempDir.resolve("web-queue.db");
        assertTrue(queue.isOpen(), "queue did not open; the tests below would be meaningless");
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    /**
     * Four enqueue threads and four claim threads at once. Every id enqueued
     * must come back from exactly one claim: an enqueue that joined the claim's
     * transaction and was rolled back is an id the browser was told about that
     * never exists, and a double claim would execute a purchase twice.
     */
    @Test
    @DisplayName("concurrent enqueue and claim loses nothing and claims nothing twice")
    void concurrentEnqueueAndClaimIsAtomic() throws Exception {
        UUID player = UUID.randomUUID();
        int threads = 4;
        int perThread = 25;
        int total = threads * perThread;

        List<String> enqueued = Collections.synchronizedList(new ArrayList<>());
        List<String> claimed = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    enqueued.add(queue.enqueue(PurchaseQueue.Type.BUY, player, "DIAMOND", 0, 0, 1));
                }
                return null;
            }));
        }
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                long deadline = System.currentTimeMillis() + 15_000L;
                while (System.currentTimeMillis() < deadline && claimed.size() < total) {
                    for (PurchaseQueue.Purchase purchase : queue.claimPending(player)) {
                        claimed.add(purchase.id());
                    }
                }
                return null;
            }));
        }

        start.countDown();
        try {
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Drain whatever the claimers did not reach before their loop ended.
        for (PurchaseQueue.Purchase purchase : queue.claimPending(player)) {
            claimed.add(purchase.id());
        }

        assertEquals(total, enqueued.size(), "an enqueue was refused");
        assertEquals(total, new HashSet<>(enqueued).size(), "duplicate purchase id");
        assertEquals(total, claimed.size(), "a purchase was claimed more than once");
        assertEquals(new HashSet<>(enqueued), new HashSet<>(claimed),
                "an enqueued purchase was never claimed, so its insert was rolled back");
    }

    @Test
    @DisplayName("a pending entry past the TTL is cancelled and can never execute")
    void stalePendingEntryIsCancelled() throws Exception {
        UUID player = UUID.randomUUID();
        String id = queue.enqueue(PurchaseQueue.Type.BUY, player, "DIAMOND", 0, 0, 1);
        assertNotNull(id);
        backdateMinutes(id, 16);

        assertEquals(1, queue.expireStale(), "the stale entry was not cancelled");
        assertTrue(queue.claimPending(player).isEmpty(),
                "a cancelled entry was handed to the executor anyway");

        PurchaseQueue.Purchase purchase = queue.find(id);
        assertEquals(PurchaseQueue.FAILED, purchase.status());
        assertTrue(purchase.resultJson().contains("expired"), purchase.resultJson());
    }

    @Test
    @DisplayName("a processing entry left by a restart is cancelled after the TTL")
    void staleProcessingEntryIsCancelled() throws Exception {
        UUID player = UUID.randomUUID();
        String id = queue.enqueue(PurchaseQueue.Type.BUY, player, "DIAMOND", 0, 0, 1);
        assertEquals(1, queue.claimPending(player).size(), "the entry was not claimed for the test");
        backdateMinutes(id, 16);

        assertEquals(1, queue.expireStale(), "the abandoned processing entry was not cancelled");
        assertEquals(PurchaseQueue.FAILED, queue.find(id).status());
        assertFalse(queue.complete(id, true, "{\"success\":true}"),
                "a cancelled entry accepted a completion");
    }

    @Test
    @DisplayName("only the first completion settles a purchase")
    void firstCompletionWins() {
        UUID player = UUID.randomUUID();
        String id = queue.enqueue(PurchaseQueue.Type.BUY, player, "DIAMOND", 0, 0, 1);
        assertEquals(1, queue.claimPending(player).size());

        assertTrue(queue.complete(id, true, "{\"success\":true}"),
                "the first completion was not accepted");
        assertFalse(queue.complete(id, false, "{\"error\":\"late\"}"),
                "a second completion was accepted");
        assertFalse(queue.complete(id, true, "{\"success\":true}"),
                "a third completion was accepted");

        PurchaseQueue.Purchase purchase = queue.find(id);
        assertEquals(PurchaseQueue.COMPLETED, purchase.status());
        assertEquals("{\"success\":true}", purchase.resultJson());
    }

    @Test
    @DisplayName("a purchase for a player who never returns cannot run later")
    void offlinePlayerPurchaseExpiresInsteadOfRunning() throws Exception {
        UUID offline = UUID.randomUUID();
        String id = queue.enqueue(PurchaseQueue.Type.SELL, offline, "DIAMOND", 0, 0, 1);
        assertTrue(queue.claimPending(UUID.randomUUID()).isEmpty(),
                "another player's claim took the entry");
        backdateMinutes(id, 16);

        queue.expireStale();

        assertTrue(queue.claimPending(offline).isEmpty(),
                "the expired entry would execute when the player logs in");
        assertEquals(PurchaseQueue.FAILED, queue.find(id).status());
    }

    private void backdateMinutes(String id, long minutes) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE web_purchases SET created_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis() - minutes * 60_000L);
            ps.setString(2, id);
            assertEquals(1, ps.executeUpdate(), "could not backdate " + id);
        }
    }
}

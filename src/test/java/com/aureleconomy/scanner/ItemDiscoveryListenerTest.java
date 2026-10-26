package com.aureleconomy.scanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // --- L2: the listener's own map, cleared on quit ---------------------

    private ItemDiscoveryListener listener;
    private UnifiedItemScanner scanner;

    @BeforeEach
    void buildListener() {
        AurelEconomy plugin = mock(AurelEconomy.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("custom-items.discovery-methods.interaction-detect", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));

        scanner = mock(UnifiedItemScanner.class);
        listener = new ItemDiscoveryListener(plugin, scanner);
    }

    @Test
    @DisplayName("L2: quitting clears the player's cooldown entry")
    void quitClearsTheCooldownEntry() {
        Player player = mockPlayer();
        listener.onPlayerInteract(interact(player));

        assertTrue(listener.lastScan.containsKey(player.getUniqueId()),
                "the cooldown entry was never written, so the rate limit does nothing");

        listener.onPlayerQuit(quit(player));

        assertFalse(listener.lastScan.containsKey(player.getUniqueId()),
                "the cooldown entry outlived the player");
    }

    @Test
    @DisplayName("L2: a returning player with the same UUID is rate-limited afresh, not blocked")
    void rejoiningScansAgain() {
        Player player = mockPlayer();
        listener.onPlayerInteract(interact(player));
        listener.onPlayerQuit(quit(player));

        listener.onPlayerInteract(interact(player));

        verify(scanner, times(2)).scanSingleItem(any(ItemStack.class),
                any(DiscoveryMethod.class));
    }

    private static Player mockPlayer() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        return player;
    }

    private static ItemStack mockItem() {
        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        return stack;
    }

    private static PlayerInteractEvent interact(Player player) {
        ItemStack item = mockItem();
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getItem()).thenReturn(item);
        return event;
    }

    private static PlayerQuitEvent quit(Player player) {
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(event.getPlayer()).thenReturn(player);
        return event;
    }
}

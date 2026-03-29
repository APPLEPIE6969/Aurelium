package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Bukkit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class to asynchronously cache OfflinePlayer names to prevent main-thread
 * stalling during GUI updates and server ticks.
 */
public class UUIDNameCache {

    private static final Map<UUID, String> CACHE = new ConcurrentHashMap<>();
    private static final String LOADING_PLACEHOLDER = "Loading...";

    /**
     * Gets the player's name from the cache. If not present, returns a placeholder
     * and triggers an asynchronous fetch.
     *
     * @param uuid The UUID of the player.
     * @return The cached name or "Loading...".
     */
    public static String getName(UUID uuid) {
        if (uuid == null) {
            return "Unknown";
        }

        if (CACHE.containsKey(uuid)) {
            return CACHE.get(uuid);
        }

        // Return a placeholder immediately while fetching asynchronously
        CACHE.put(uuid, LOADING_PLACEHOLDER);

        Bukkit.getScheduler().runTaskAsynchronously(AurelEconomy.getInstance(), () -> {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null) {
                CACHE.put(uuid, name);
            } else {
                CACHE.put(uuid, "Unknown");
            }
        });

        return LOADING_PLACEHOLDER;
    }
}

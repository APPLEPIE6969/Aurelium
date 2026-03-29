package com.aureleconomy.utils;

import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerNameCache {
    private static final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public static String getName(UUID uuid) {
        if (uuid == null) return "Unknown";

        return cache.computeIfAbsent(uuid, k -> {
            String name = Bukkit.getOfflinePlayer(k).getName();
            return name != null ? name : "Unknown";
        });
    }
}

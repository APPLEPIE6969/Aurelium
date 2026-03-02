package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ChatPromptManager implements Listener {

    private final AurelEconomy plugin;
    private final Map<UUID, Consumer<String>> pendingPrompts = new HashMap<>();

    public ChatPromptManager(AurelEconomy plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void prompt(Player player, Consumer<String> onChat) {
        pendingPrompts.put(player.getUniqueId(), onChat);
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (pendingPrompts.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            String message = event.getMessage();
            Consumer<String> action = pendingPrompts.remove(player.getUniqueId());

            // Run action synchronously
            Bukkit.getScheduler().runTask(plugin, () -> {
                action.accept(message);
            });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingPrompts.remove(event.getPlayer().getUniqueId());
    }
}

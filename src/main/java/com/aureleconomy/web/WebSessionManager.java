package com.aureleconomy.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Manages authentication sessions for the web dashboard.
 * Each player gets a unique token via /web that maps to their UUID.
 * Includes scheduled periodic cleanup to prevent memory leaks.
 */
public class WebSessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long timeoutMs;
    private final JavaPlugin plugin;
    private BukkitTask cleanupTask;

    public WebSessionManager(JavaPlugin plugin, long timeoutMinutes) {
        this.plugin = plugin;
        this.timeoutMs = timeoutMinutes * 60 * 1000;
        startCleanupTask();
    }

    /**
     * Start a periodic cleanup task that removes expired sessions every 5 minutes.
     * Prevents memory leaks from sessions that are never validated again.
     */
    private void startCleanupTask() {
        this.cleanupTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::cleanup,
                6000L,
                6000L
        );
    }

    /**
     * Stop the cleanup task. Call on plugin disable.
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        sessions.clear();
        playerTokens.clear();
    }

    /**
     * Generate a new session token for a player (invalidates any previous session).
     */
    public String createSession(UUID playerUuid) {
        String existing = playerTokens.get(playerUuid);
        if (existing != null) {
            sessions.remove(existing);
        }

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Session session = new Session(playerUuid, System.currentTimeMillis());
        sessions.put(token, session);
        playerTokens.put(playerUuid, token);

        return token;
    }

    /** Validate a token and return the player UUID, or null if invalid/expired. */
    public UUID validate(String token) {
        if (token == null)
            return null;

        Session session = sessions.get(token);
        if (session == null)
            return null;

        if (System.currentTimeMillis() - session.lastActivity > timeoutMs) {
            sessions.remove(token);
            playerTokens.remove(session.playerUuid);
            return null;
        }

        session.lastActivity = System.currentTimeMillis();
        return session.playerUuid;
    }

    /** Remove all expired sessions. */
    public void cleanup() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastActivity > timeoutMs) {
                playerTokens.remove(entry.getValue().playerUuid);
                return true;
            }
            return false;
        });
    }

    /** Invalidate a player's session (e.g., on disconnect). */
    public void invalidate(UUID playerUuid) {
        String token = playerTokens.remove(playerUuid);
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * Invalidate a session by its token, for an explicit logout.
     *
     * <p>The dashboard link carries the token in the URL, so a player who logs out
     * on a shared machine could otherwise leave it usable until the session idles
     * out. Removing the row revokes it immediately.
     *
     * @return true if a live session was removed
     */
    public boolean invalidateToken(String token) {
        if (token == null) {
            return false;
        }
        Session session = sessions.remove(token);
        if (session == null) {
            return false;
        }
        // Only drop the player's index if it still points at this token;
        // a newer /web run has already replaced it.
        playerTokens.remove(session.playerUuid, token);
        return true;
    }

    private static class Session {
        final UUID playerUuid;
        long lastActivity;

        Session(UUID playerUuid, long createdAt) {
            this.playerUuid = playerUuid;
            this.lastActivity = createdAt;
        }
    }
}

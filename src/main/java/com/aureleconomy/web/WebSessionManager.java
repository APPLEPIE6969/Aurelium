package com.aureleconomy.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages authentication sessions for the web dashboard.
 * Each player gets a unique token via /web that maps to their UUID.
 */
public class WebSessionManager {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerTokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final long timeoutMs;

    public WebSessionManager(long timeoutMinutes) {
        this.timeoutMs = timeoutMinutes * 60 * 1000;
    }

    /**
     * Generate a new session token for a player (invalidates any previous session).
     */
    public String createSession(UUID playerUuid) {
        // Revoke existing session
        String existing = playerTokens.get(playerUuid);
        if (existing != null) {
            sessions.remove(existing);
        }

        // Generate 32-byte random token
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

        // Check expiration
        if (System.currentTimeMillis() - session.lastActivity > timeoutMs) {
            sessions.remove(token);
            playerTokens.remove(session.playerUuid);
            return null;
        }

        // Refresh activity
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

    private static class Session {
        final UUID playerUuid;
        long lastActivity;

        Session(UUID playerUuid, long createdAt) {
            this.playerUuid = playerUuid;
            this.lastActivity = createdAt;
        }
    }
}

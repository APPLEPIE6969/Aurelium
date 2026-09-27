package com.aureleconomy.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The avatar url is built from a config value and a player name. It is keyed on
 * the name because an offline-mode server's uuid is derived from the name and
 * will never match a Mojang profile, so the name is the only handle that works
 * there.
 */
class AvatarUrlTest {

    private static String url(String provider, int size, String name) {
        return DashboardApiHandler.avatarUrl(provider, size, name);
    }

    @Test
    @DisplayName("the default provider builds an mc-heads url at the requested size")
    void defaultProvider() {
        assertEquals("https://mc-heads.net/avatar/Notch/64", url("mc-heads", 64, "Notch"));
    }

    @Test
    @DisplayName("minotar is supported as an alternative")
    void minotarProvider() {
        assertEquals("https://minotar.net/helm/Notch/64.png", url("minotar", 64, "Notch"));
    }

    @Test
    @DisplayName("'none' disables avatars so the frontend shows the initial")
    void noneDisables() {
        assertEquals("", url("none", 64, "Notch"));
    }

    @Test
    @DisplayName("an unknown provider is treated as off rather than guessed at")
    void unknownProviderDisables() {
        // A typo in config.yml should not send the player name to a random host.
        assertEquals("", url("sketchfab", 64, "Notch"));
    }

    @Test
    @DisplayName("sizes other than 16/32/64 fall back to 64")
    void clampsSize() {
        assertEquals("https://mc-heads.net/avatar/Notch/16", url("mc-heads", 16, "Notch"));
        assertEquals("https://mc-heads.net/avatar/Notch/32", url("mc-heads", 32, "Notch"));
        assertEquals("https://mc-heads.net/avatar/Notch/64", url("mc-heads", 128, "Notch"));
        assertEquals("https://mc-heads.net/avatar/Notch/64", url("mc-heads", 0, "Notch"));
    }

    @Test
    @DisplayName("a blank or missing name yields no url")
    void blankName() {
        assertEquals("", url("mc-heads", 64, null));
        assertEquals("", url("mc-heads", 64, ""));
        assertEquals("", url("mc-heads", 64, "   "));
    }

    @Test
    @DisplayName("names are url-encoded so they cannot break out of the path")
    void encodesName() {
        // Offline-mode servers allow a wide range of characters in a username, so
        // the name has to be escaped before it goes anywhere near a url path.
        String name = "a b/c?d&e";
        String built = url("mc-heads", 64, name);
        assertEquals("https://mc-heads.net/avatar/"
                + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/64", built);

        // The path separator, query and fragment markers must not survive raw,
        // or a name could redirect the request to another host.
        String tail = built.substring("https://mc-heads.net/avatar/".length(), built.length() - "/64".length());
        assertFalse(tail.contains("/"), "slash survived: " + tail);
        assertFalse(tail.contains("?"), "query marker survived: " + tail);
        assertFalse(tail.contains("&"), "ampersand survived: " + tail);
        assertFalse(tail.contains(" "), "space survived: " + tail);
        assertTrue(tail.contains("%2F"), "expected an encoded slash in: " + tail);
    }

    @Test
    @DisplayName("underscores in a username survive")
    void keepsUnderscore() {
        assertEquals("https://mc-heads.net/avatar/jeb_/64", url("mc-heads", 64, "jeb_"));
    }
}

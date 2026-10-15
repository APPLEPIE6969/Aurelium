package com.aureleconomy.config;

import com.aureleconomy.AurelEconomy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Exercises the real config migration in AurelEconomy.upgradeConfig.
 *
 * <p>That method is not a conventional version-to-version upgrade. It runs on
 * every startup, not only when the version changed: it reads the user's config,
 * extracts every value including nested sections, deletes the file, writes a
 * fresh copy of the shipped default, then merges the user's values back on top.
 * So the property that matters is not "does 1.5.4 become 1.5.5" but "does
 * anything the owner set survive that round trip, and do new defaults still
 * appear".
 *
 * <p>The private helpers are called reflectively on purpose. A copy of the logic
 * in the test would keep passing after the real implementation was changed,
 * which is exactly the regression this is meant to catch.
 *
 * <p>The "old" config used here is the one a real 1.5.4 server is running, with
 * owner customisations added on top, rather than a hand-written fixture.
 */
class ConfigMigrationTest {

    /** The config a real server is running, shipped with 1.5.4. */
    private static final String LIVE_CONFIG =
            "D:/aurelium-server-26/plugins/Aurelium/config.yml";

    /**
     * The default config that ships inside the jar.
     *
     * <p>Resolved by walking up from the working directory rather than by a
     * relative path. Gradle runs a module's tests from that module's directory,
     * so "src/main/resources/config.yml" does not resolve and the test would
     * silently load an empty config and fail for the wrong reason.
     */
    private static final String SHIPPED_CONFIG = findProjectRoot()
            + "/src/main/resources/config.yml";

    private static String findProjectRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("src/main/resources/config.yml"))) {
                return dir.toString();
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "could not locate the project root from " + Path.of("").toAbsolutePath());
    }

    private AurelEconomy plugin;
    private Method deepExtract;
    private Method deepSet;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(AurelEconomy.class);
        deepExtract = AurelEconomy.class.getDeclaredMethod(
                "deepExtract", ConfigurationSection.class);
        deepExtract.setAccessible(true);
        deepSet = AurelEconomy.class.getDeclaredMethod(
                "deepSet", ConfigurationSection.class, String.class, Object.class);
        deepSet.setAccessible(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extract(ConfigurationSection section) throws Exception {
        return (Map<String, Object>) deepExtract.invoke(plugin, section);
    }

    private void set(ConfigurationSection target, String key, Object value) throws Exception {
        deepSet.invoke(plugin, target, key, value);
    }

    /** Reproduces upgradeConfig's body, minus the file delete, which needs a live plugin. */
    private YamlConfiguration migrate(YamlConfiguration oldConfig, YamlConfiguration defaults)
            throws Exception {
        Map<String, Object> userValues = extract(oldConfig);
        YamlConfiguration fresh = YamlConfiguration.loadConfiguration(
                fromString(dump(defaults)));
        for (Map.Entry<String, Object> entry : userValues.entrySet()) {
            if (entry.getKey().equals("config-version")) continue;
            set(fresh, entry.getKey(), entry.getValue());
        }
        return YamlConfiguration.loadConfiguration(fromString(dump(fresh)));
    }

    private static java.io.Reader fromString(String text) {
        return new java.io.StringReader(text);
    }

    private static String dump(YamlConfiguration config) {
        return config.saveToString();
    }

    private YamlConfiguration shipped() throws Exception {
        return YamlConfiguration.loadConfiguration(new File(SHIPPED_CONFIG));
    }

    /**
     * Builds the "before" state: the real running config, with owner changes
     * layered on so the test has something it can actually assert survived.
     */
    private YamlConfiguration liveConfigWithOwnerChanges(Path dir) throws Exception {
        YamlConfiguration old;
        if (Files.exists(Path.of(LIVE_CONFIG))) {
            old = YamlConfiguration.loadConfiguration(new File(LIVE_CONFIG));
        } else {
            // Keep the test runnable on a machine without that server directory.
            old = shipped();
        }

        // Owner customisations: a changed default, a nested value, a runtime key,
        // and a key the shipped default has never heard of.
        old.set("database.type", "mysql");
        old.set("database.mysql.host", "db.example.com");
        old.set("database.mysql.ssl-mode", "REQUIRED");
        old.set("economy.starting-balance", 12345.67);
        old.set("web.cloud.server-id", "my-server-id");
        old.set("web.cloud.api-key", "secret-key-value");
        old.set("last-version", "1.5.4");
        old.set("my-own-custom-setting", "do not lose me");

        // Market prices the owner set. These are the highest-value thing in the
        // file and the most likely casualty of a botched merge.
        old.set("market-items.DIAMOND.buy", 1234.5);
        old.set("market-items.DIAMOND.sell", 900.0);
        old.set("market-items.SPAWNER_IRON_GOLEM_SPAWNER.buy", 750000.0);
        old.set("market-items.SPAWNER_IRON_GOLEM_SPAWNER.sell", 500000.0);
        old.set("market-items.SPAWNER_IRON_GOLEM_SPAWNER.currency", "Aurels");

        return old;
    }

    @Test
    @DisplayName("owner values survive the round trip")
    void ownerValuesSurvive() throws Exception {
        YamlConfiguration result = migrate(liveConfigWithOwnerChanges(null), shipped());

        assertEquals("mysql", result.getString("database.type"),
                "changed default was lost");
        assertEquals("db.example.com", result.getString("database.mysql.host"),
                "nested mysql host was lost");
        assertEquals(12345.67, result.getDouble("economy.starting-balance"), 0.001,
                "nested economy value was lost");
        assertEquals("my-server-id", result.getString("web.cloud.server-id"),
                "nested cloud value was lost");
        assertEquals("secret-key-value", result.getString("web.cloud.api-key"),
                "api key was lost");
    }

    @Test
    @DisplayName("market prices, including spawners, survive")
    void marketPricesSurvive() throws Exception {
        YamlConfiguration result = migrate(liveConfigWithOwnerChanges(null), shipped());

        assertEquals(1234.5, result.getDouble("market-items.DIAMOND.buy"), 0.001);
        assertEquals(900.0, result.getDouble("market-items.DIAMOND.sell"), 0.001);

        // The spawner key is the one that caused the pricing bug, so it is the
        // single most valuable value to prove is not dropped by a migration.
        assertEquals(750000.0, result.getDouble("market-items.SPAWNER_IRON_GOLEM_SPAWNER.buy"), 0.001,
                "spawner buy price was lost in migration");
        assertEquals(500000.0, result.getDouble("market-items.SPAWNER_IRON_GOLEM_SPAWNER.sell"), 0.001);
        assertEquals("Aurels", result.getString("market-items.SPAWNER_IRON_GOLEM_SPAWNER.currency"));
    }

    @Test
    @DisplayName("a key the default config has never heard of is kept")
    void unknownKeysSurvive() throws Exception {
        YamlConfiguration result = migrate(liveConfigWithOwnerChanges(null), shipped());

        assertEquals("do not lose me", result.getString("my-own-custom-setting"),
                "an owner-added key was dropped");
        assertEquals("1.5.4", result.getString("last-version"));
    }

    @Test
    @DisplayName("new defaults are present after migrating an old config")
    void newDefaultsAppear() throws Exception {
        YamlConfiguration defaults = shipped();

        // Simulate an older config that predates the current shape, which is what
        // a real 1.5.4 -> 1.5.5 upgrade will look like.
        YamlConfiguration old = YamlConfiguration.loadConfiguration(fromString("""
                config-version: 1
                database:
                  type: sqlite
                web:
                  enabled: true
                  port: 8585
                """));

        YamlConfiguration result = migrate(old, defaults);

        // Keys that only exist in the new default must show up even though the
        // old config never mentioned them.
        for (String key : new String[]{
                "database.mysql.ssl-mode",
                "web.mode",
                "web.local.host",
                "web-queue.type"}) {
            assertTrue(result.contains(key),
                    "new default key " + key + " did not appear after migration");
        }

        assertEquals(defaults.getInt("config-version"),
                result.getInt("config-version"),
                "config-version should track the shipped default, not the old value");
    }

    @Test
    @DisplayName("the shipped default is what a fresh install gets, and it is complete")
    void shippedDefaultIsSelfConsistent() throws Exception {
        YamlConfiguration defaults = shipped();

        assertTrue(defaults.contains("config-version"),
                "shipped config.yml has no config-version");
        assertEquals(2, defaults.getInt("config-version"),
                "config-version changed; upgradeConfig must be able to recognise it");
    }

    @Test
    @DisplayName("migrating twice is stable")
    void migrationIsIdempotent() throws Exception {
        YamlConfiguration defaults = shipped();
        YamlConfiguration once = migrate(liveConfigWithOwnerChanges(null), defaults);
        YamlConfiguration twice = migrate(once, defaults);

        assertEquals(750000.0, twice.getDouble("market-items.SPAWNER_IRON_GOLEM_SPAWNER.buy"), 0.001,
                "second migration lost the spawner price");
        assertEquals("mysql", twice.getString("database.type"));
        assertEquals(dump(once), dump(twice),
                "a second startup changed the config again, so migration is not stable");
    }
}
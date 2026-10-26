package com.aureleconomy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M5: plugin.yml declared the aureleconomy.stocks permission but never set it on
 * the command node, so Bukkit let any sender run /stocks. Bukkit enforces the
 * node's permission before the executor is called, so the declaration is the fix.
 */
class StocksPermissionTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pluginYml() {
        InputStream in = StocksPermissionTest.class.getClassLoader().getResourceAsStream("plugin.yml");
        return new Yaml().load(in);
    }

    @Test
    @DisplayName("the /stocks command node carries the aureleconomy.stocks permission")
    void stocksCommandRequiresItsPermission() {
        Map<String, Object> commands = (Map<String, Object>) pluginYml().get("commands");
        Map<String, Object> stocks = (Map<String, Object>) commands.get("stocks");

        assertEquals("aureleconomy.stocks", stocks.get("permission"),
                "/stocks ran for every sender because the node had no permission");
    }

    @Test
    @DisplayName("the permission itself is declared and defaults to true")
    void permissionIsDeclaredWithDefaultTrue() {
        Map<String, Object> permissions = (Map<String, Object>) pluginYml().get("permissions");
        Map<String, Object> stocks = (Map<String, Object>) permissions.get("aureleconomy.stocks");

        assertEquals("true", String.valueOf(stocks.get("default")),
                "the declared permission must keep its default: true");
    }
}

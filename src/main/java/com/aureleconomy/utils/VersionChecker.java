package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class VersionChecker {

    private static final String MODRINTH_API = "https://api.modrinth.com/v2/project/aurelium/version";
    private static final String USER_AGENT = "Aurelium-VersionChecker/1.0 (modrinth.com/plugin/aurelium)";

    public static void checkVersion(AurelEconomy plugin) {
        String pluginVersion = plugin.getPluginMeta().getVersion();
        String mcVersion = extractMinecraftVersion(plugin);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                String gameVersionsParam = "[\"" + mcVersion + "\"]";
                String encodedParam = URLEncoder.encode(gameVersionsParam, StandardCharsets.UTF_8);
                String url = MODRINTH_API + "?game_versions=" + encodedParam;

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("[Aurelium] Could not check for updates (HTTP " + response.statusCode() + ")");
                    return;
                }

                JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();
                if (versions.isEmpty()) {
                    plugin.getLogger().info("[Aurelium] No releases found for Minecraft " + mcVersion + ".");
                    return;
                }

                List<String> versionNumbers = new ArrayList<>();
                for (JsonElement element : versions) {
                    JsonObject versionObj = element.getAsJsonObject();
                    String versionNumber = versionObj.get("version_number").getAsString();
                    versionNumbers.add(versionNumber);
                }

                String latestVersion = versionNumbers.get(0);

                int behindCount = -1;
                for (int i = 0; i < versionNumbers.size(); i++) {
                    if (versionsMatch(versionNumbers.get(i), pluginVersion)) {
                        behindCount = i;
                        break;
                    }
                }

                if (behindCount == -1) {
                    plugin.getLogger().info("[Aurelium] Version " + pluginVersion + " not found on Modrinth for MC " + mcVersion + ". Latest is " + latestVersion + ".");
                    return;
                }

                if (behindCount == 0) {
                    plugin.getLogger().info("[Aurelium] You are running the latest version (" + pluginVersion + ") for Minecraft " + mcVersion + ".");
                } else {
                    plugin.getLogger().warning("[Aurelium] You are " + behindCount + " version" + (behindCount == 1 ? "" : "s") + " behind based on your server's Minecraft version. Latest is " + latestVersion + ", you have " + pluginVersion + ".");
                    plugin.getLogger().warning("[Aurelium] Download the latest version at: https://modrinth.com/plugin/aurelium/versions");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("[Aurelium] Could not check for updates: " + e.getMessage());
            }
        });
    }

    private static String extractMinecraftVersion(AurelEconomy plugin) {
        String bukkitVersion = plugin.getServer().getBukkitVersion();
        if (bukkitVersion == null || bukkitVersion.isEmpty()) {
            return "unknown";
        }
        String mcVersion = bukkitVersion.split("-")[0];
        return mcVersion;
    }

    private static boolean versionsMatch(String modrinthVersion, String pluginVersion) {
        String normalizedModrinth = normalizeVersion(modrinthVersion);
        String normalizedPlugin = normalizeVersion(pluginVersion);
        return normalizedModrinth.equals(normalizedPlugin);
    }

    private static String normalizeVersion(String version) {
        return version.replaceAll("-.*$", "").trim();
    }
}

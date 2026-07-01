package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

public class VaultInstaller {

    private static final String VAULT_MODRINTH_URL =
            "https://cdn.modrinth.com/data/9uLXB4Yz/versions/7VNmMqhn/Vault.jar";

    public static void install(AurelEconomy plugin) {
        Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vault != null) {
            return;
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        File vaultJar = new File(pluginsDir, "Vault.jar");

        if (vaultJar.exists()) {
            plugin.getComponentLogger().warn("Vault.jar exists but is not loaded. A restart might be required.");
            return;
        }

        plugin.getComponentLogger().info("Vault not found. Attempting to auto-install Vault from Modrinth...");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VAULT_MODRINTH_URL))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            Path targetPath = vaultJar.toPath();
            HttpResponse<Path> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(targetPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));

            if (response.statusCode() >= 300) {
                Files.deleteIfExists(targetPath);
                plugin.getComponentLogger().error("Failed to download Vault.jar (HTTP " + response.statusCode() + ")");
                return;
            }

            long fileSize = Files.size(targetPath);
            if (fileSize < 1000) {
                Files.deleteIfExists(targetPath);
                plugin.getComponentLogger().error("Downloaded Vault.jar is too small (" + fileSize + " bytes) — likely an error page");
                return;
            }

            plugin.getComponentLogger().info("Vault.jar has been installed to " + vaultJar.getAbsolutePath()
                    + " (" + fileSize + " bytes)");
            plugin.getComponentLogger().error(">>> IMPORTANT: PLEASE RESTART THE SERVER TO LOAD VAULT! <<<");

            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("--------------------------------------------------", NamedTextColor.RED));
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("Aurelium has installed Vault.jar!", NamedTextColor.GOLD));
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("You MUST restart the server for it to take effect.", NamedTextColor.RED));
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("--------------------------------------------------", NamedTextColor.RED));

        } catch (IOException e) {
            plugin.getComponentLogger().error("Failed to install Vault.jar (I/O error)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getComponentLogger().error("Vault download was interrupted");
        } catch (Exception e) {
            plugin.getComponentLogger().error("Failed to install Vault.jar", e);
        }
    }
}

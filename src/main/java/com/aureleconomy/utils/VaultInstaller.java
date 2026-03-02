package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class VaultInstaller {

    public static void install(AurelEconomy plugin) {
        // Check if Vault is loaded
        Plugin vault = plugin.getServer().getPluginManager().getPlugin("Vault");
        if (vault != null) {
            return; // Vault is present
        }

        File pluginsDir = plugin.getDataFolder().getParentFile();
        File vaultJar = new File(pluginsDir, "Vault.jar");

        if (vaultJar.exists()) {
            plugin.getComponentLogger().warn("Vault.jar exists but is not loaded. A restart might be required.");
            return;
        }

        plugin.getComponentLogger().info("Vault not found. Attempting to auto-install Vault...");

        try (InputStream in = plugin.getResource("Vault.jar")) {
            if (in == null) {
                plugin.getComponentLogger().error("Could not find embedded Vault.jar!");
                return;
            }

            Files.copy(in, vaultJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getComponentLogger().info("Vault.jar has been installed to " + vaultJar.getAbsolutePath());
            plugin.getComponentLogger().error(">>> IMPORTANT: PLEASE RESTART THE SERVER TO LOAD VAULT! <<<");

            // Announce to console with color if possible or just log error to stand out
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("--------------------------------------------------", NamedTextColor.RED));
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("Aurelium has installed Vault.jar!", NamedTextColor.GOLD));
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("You MUST restart the server for it to take effect.", NamedTextColor.RED));
            plugin.getServer().getConsoleSender().sendMessage(
                    Component.text("--------------------------------------------------", NamedTextColor.RED));

        } catch (IOException e) {
            plugin.getComponentLogger().error("Failed to install Vault.jar", e);
        }
    }
}

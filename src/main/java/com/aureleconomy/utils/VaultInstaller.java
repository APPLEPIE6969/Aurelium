package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Downloads Vault when the server does not have it.
 *
 * <p>The download used to run during {@code onEnable} with a 15 second connect
 * timeout and a 60 second request timeout, so a slow or unreachable GitHub kept the
 * main thread waiting for up to 75 seconds before the server finished starting
 * (M12). It also wrote whatever came back straight to {@code plugins/Vault.jar}
 * without checking what it was. The download now runs on an async thread, and the
 * jar is only moved into place when its SHA-256 matches the pinned release.
 */
public final class VaultInstaller {

    private static final String VAULT_GITHUB_URL =
            "https://github.com/MilkBowl/Vault/releases/download/1.7.3/Vault.jar";

    /**
     * SHA-256 of the Vault 1.7.3 jar behind {@link #VAULT_GITHUB_URL} (272,259
     * bytes, {@code version: 1.7.3-b131}). A release asset is replaced rather than
     * versioned upstream, so this is what makes "the same URL" mean "the same jar".
     */
    static final String VAULT_SHA256 =
            "a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d";

    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 15;
    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 60;

    /** A download smaller than this is an error page, not a jar. */
    private static final long MINIMUM_JAR_BYTES = 1000;

    private VaultInstaller() {
    }

    /**
     * Called from {@code onEnable}. Everything here is local and cheap; the network
     * work is handed to an async task so a slow download cannot hold up startup.
     */
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

        if (!plugin.getConfig().getBoolean("vault.auto-install", true)) {
            plugin.getComponentLogger().info(
                    "Vault not found and vault.auto-install is disabled. Running without Vault.");
            return;
        }

        plugin.getComponentLogger().info("Vault not found. Downloading Vault.jar in the background...");
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> download(plugin, vaultJar));
        } catch (RuntimeException e) {
            // Failing to schedule the download must not fail the enable. The server
            // simply runs without Vault, which is what it did before this feature.
            plugin.getComponentLogger().warn("Could not start the Vault download: " + e.getMessage());
        }
    }

    /**
     * Downloads to a temporary file next to the target and installs it only after
     * the checksum matches. Runs off the main thread; every failure is logged and
     * the server keeps running without Vault.
     */
    static void download(AurelEconomy plugin, File vaultJar) {
        Path target = vaultJar.toPath();
        Path staged = target.resolveSibling(vaultJar.getName() + ".download");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(connectTimeout(plugin.getConfig()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(VAULT_GITHUB_URL))
                    .timeout(requestTimeout(plugin.getConfig()))
                    .GET()
                    .build();

            Files.createDirectories(target.getParent());
            plugin.getComponentLogger().info("Downloading " + VAULT_GITHUB_URL + " ...");

            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() >= 300) {
                plugin.getComponentLogger().error("Failed to download Vault.jar (HTTP "
                        + response.statusCode() + "). The server will run without Vault.");
                return;
            }

            try (InputStream body = response.body()) {
                Files.copy(body, staged, StandardCopyOption.REPLACE_EXISTING);
            }

            plugin.getComponentLogger().info("Downloaded " + Files.size(staged)
                    + " bytes; verifying the checksum...");

            if (!acceptDownload(plugin, staged, vaultJar, VAULT_SHA256)) {
                plugin.getComponentLogger().warn(
                        "Vault was not installed. The server will keep running without it.");
                return;
            }

            long fileSize = Files.size(target);
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
            plugin.getComponentLogger().error(
                    "Failed to install Vault.jar (I/O error). The server will keep running without Vault.", e);
            deleteQuietly(staged);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getComponentLogger().error(
                    "Vault download was interrupted. The server will keep running without Vault.");
            deleteQuietly(staged);
        } catch (RuntimeException e) {
            plugin.getComponentLogger().error(
                    "Failed to install Vault.jar. The server will keep running without Vault.", e);
            deleteQuietly(staged);
        }
    }

    /**
     * Installs a completed download when, and only when, it is the jar that was
     * pinned. Returns false and removes the staged file otherwise, so a replaced or
     * truncated asset can never land in {@code plugins/}.
     */
    static boolean acceptDownload(AurelEconomy plugin, Path staged, File vaultJar, String expectedSha256) {
        try {
            long fileSize = Files.size(staged);
            if (fileSize < MINIMUM_JAR_BYTES) {
                Files.deleteIfExists(staged);
                plugin.getComponentLogger().error("Downloaded Vault.jar is too small ("
                        + fileSize + " bytes), likely an error page; not installing it");
                return false;
            }

            String actualSha256 = sha256(staged);
            if (expectedSha256 == null || !expectedSha256.equalsIgnoreCase(actualSha256)) {
                Files.deleteIfExists(staged);
                plugin.getComponentLogger().error("Refusing to install Vault.jar: checksum mismatch"
                        + " (expected " + expectedSha256 + ", got " + actualSha256 + ")");
                return false;
            }

            Files.move(staged, vaultJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            plugin.getComponentLogger().error("Could not install the downloaded Vault.jar", e);
            deleteQuietly(staged);
            return false;
        }
    }

    /** Lowercase hex SHA-256 of a file. */
    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JRE provides SHA-256", e);
        }
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Duration connectTimeout(FileConfiguration config) {
        return timeout(config, "vault.connect-timeout-seconds", DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    static Duration requestTimeout(FileConfiguration config) {
        return timeout(config, "vault.request-timeout-seconds", DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    /**
     * Reads one timeout from config. A missing, zero, negative or absurd value falls
     * back to the default: {@link HttpRequest.Builder#timeout} rejects a non-positive
     * duration, and a typo should not turn the download into one that never ends.
     */
    private static Duration timeout(FileConfiguration config, String path, int defaultSeconds) {
        int seconds = config == null ? defaultSeconds : config.getInt(path, defaultSeconds);
        if (seconds <= 0 || seconds > 300) {
            return Duration.ofSeconds(defaultSeconds);
        }
        return Duration.ofSeconds(seconds);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover .download file is harmless and is replaced next time.
        }
    }
}

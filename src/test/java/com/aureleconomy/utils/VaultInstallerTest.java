package com.aureleconomy.utils;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Vault auto-installer accepts (M12).
 *
 * <p>The download used to be written straight to {@code plugins/Vault.jar}, so a
 * replaced release asset, an error page or a truncated transfer became a jar the
 * server would try to load on the next start. The download is now staged and only
 * moved into place when its SHA-256 matches the pinned release, and the request
 * timeouts come from config rather than a hardcoded wait on the main thread.
 */
class VaultInstallerTest {

    @TempDir
    Path tempDir;

    private AurelEconomy plugin() {
        AurelEconomy plugin = Mockito.mock(AurelEconomy.class);
        Mockito.when(plugin.getComponentLogger()).thenReturn(Mockito.mock(ComponentLogger.class));
        return plugin;
    }

    private Path stagedFile(String name, int bytes) throws Exception {
        Path file = tempDir.resolve(name);
        byte[] content = new byte[bytes];
        java.util.Arrays.fill(content, (byte) 'V');
        Files.write(file, content);
        return file;
    }

    @Test
    @DisplayName("a download that matches the pinned checksum is installed")
    void matchingChecksumIsInstalled() throws Exception {
        Path staged = stagedFile("Vault.jar.download", 4096);
        File target = tempDir.resolve("Vault.jar").toFile();
        String expected = VaultInstaller.sha256(staged);

        boolean installed = VaultInstaller.acceptDownload(plugin(), staged, target, expected);

        assertTrue(installed, "a matching jar has to be installed");
        assertTrue(target.exists(), "the jar was not moved into place");
        assertFalse(Files.exists(staged), "the staged file should be gone after the move");
        assertEquals(expected, VaultInstaller.sha256(target.toPath()),
                "the installed file is not the verified file");
    }

    @Test
    @DisplayName("a download whose checksum does not match is refused and removed")
    void mismatchedChecksumIsRefused() throws Exception {
        Path staged = stagedFile("Vault.jar.download", 4096);
        File target = tempDir.resolve("Vault.jar").toFile();

        boolean installed = VaultInstaller.acceptDownload(plugin(), staged, target, "0".repeat(64));

        assertFalse(installed, "a jar that is not the pinned one must not be installed");
        assertFalse(target.exists(), "the refused download was written into plugins/ anyway");
        assertFalse(Files.exists(staged), "the refused download was left on disk");
    }

    @Test
    @DisplayName("a file too small to be a jar is refused even when its hash matches")
    void errorPageIsRefused() throws Exception {
        Path staged = stagedFile("Vault.jar.download", 200);
        File target = tempDir.resolve("Vault.jar").toFile();
        String expected = VaultInstaller.sha256(staged);

        boolean installed = VaultInstaller.acceptDownload(plugin(), staged, target, expected);

        assertFalse(installed, "a 200-byte file is an error page, not Vault");
        assertFalse(target.exists());
    }

    @Test
    @DisplayName("the download timeouts come from config")
    void timeoutsAreReadFromConfig() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("vault.connect-timeout-seconds", 5);
        config.set("vault.request-timeout-seconds", 12);

        assertEquals(Duration.ofSeconds(5), VaultInstaller.connectTimeout(config));
        assertEquals(Duration.ofSeconds(12), VaultInstaller.requestTimeout(config));
    }

    @Test
    @DisplayName("missing or nonsense timeouts fall back to the defaults")
    void nonsenseTimeoutsFallBack() {
        assertEquals(Duration.ofSeconds(15), VaultInstaller.connectTimeout(new YamlConfiguration()));
        assertEquals(Duration.ofSeconds(60), VaultInstaller.requestTimeout(new YamlConfiguration()));
        assertEquals(Duration.ofSeconds(15), VaultInstaller.connectTimeout(null));

        YamlConfiguration zero = new YamlConfiguration();
        zero.set("vault.connect-timeout-seconds", 0);
        zero.set("vault.request-timeout-seconds", -5);
        assertEquals(Duration.ofSeconds(15), VaultInstaller.connectTimeout(zero),
                "a zero timeout would make HttpRequest reject the request");
        assertEquals(Duration.ofSeconds(60), VaultInstaller.requestTimeout(zero));

        YamlConfiguration absurd = new YamlConfiguration();
        absurd.set("vault.connect-timeout-seconds", 9999);
        assertEquals(Duration.ofSeconds(15), VaultInstaller.connectTimeout(absurd),
                "a typo must not turn into a wait measured in hours");
    }

    @Test
    @DisplayName("the pinned checksum is a SHA-256 digest of the release")
    void pinnedChecksumLooksLikeASha256() {
        assertEquals(64, VaultInstaller.VAULT_SHA256.length(), "not a SHA-256 hex digest");
        assertTrue(VaultInstaller.VAULT_SHA256.matches("[0-9a-f]{64}"),
                "the pin has to be lowercase hex, which is what sha256() returns");

        // A 272,259-byte Vault 1.7.3 jar hashes to this; an empty or placeholder
        // pin would still pass the shape checks above, so it is named explicitly.
        assertEquals("a6b5ed97f43a5cf5bbaf00a7c8cd23c5afc9bd003f849875af8b36e6cf77d01d",
                VaultInstaller.VAULT_SHA256);
    }
}

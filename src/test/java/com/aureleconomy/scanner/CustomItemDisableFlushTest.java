package com.aureleconomy.scanner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.market.MarketManager;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

/**
 * L3: onDisable never flushed the registry, and the periodic save only runs
 * every five minutes, so registrations made in that window died with the
 * process. The disable-time flush is synchronous because the scheduler is
 * already gone when onDisable runs.
 */
class CustomItemDisableFlushTest {

    private static final String ID = "test:ruby";
    private static final String CURRENCY = "coins";
    private static final BigDecimal BUY = new BigDecimal("100.00");
    private static final BigDecimal SELL = new BigDecimal("50.00");

    @TempDir
    Path tempDir;

    private AurelEconomy plugin;
    private DatabaseManager manager;
    private CustomItemRegistry registry;
    private MockedStatic<Bukkit> bukkit;
    private ItemStack stack;

    @BeforeEach
    void openRegistry() {
        File dataFolder = tempDir.resolve("data").toFile();
        //noinspection ResultOfMethodCallIgnored
        dataFolder.mkdirs();

        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "sqlite");
        config.set("database.file", "database.db");
        config.set("custom-items.auto-add-to-market", false);
        config.set("custom-items.enabled", true);
        config.set("economy.default-currency", CURRENCY);
        config.set("market.dynamic-pricing", false);
        config.set("market.alerts.enabled", false);

        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getComponentLogger()).thenReturn(mock(ComponentLogger.class));
        when(plugin.namespace()).thenReturn("aurelium");

        manager = new DatabaseManager(plugin);
        assertTrue(manager.initialize(), "initialize() must succeed on an empty data folder");
        when(plugin.getDatabaseManager()).thenReturn(manager);

        when(plugin.getMarketManager()).thenReturn(mock(MarketManager.class));
        EconomyManager economy = mock(EconomyManager.class);
        when(economy.getDefaultCurrency()).thenReturn(CURRENCY);
        when(plugin.getEconomyManager()).thenReturn(economy);

        bukkit = mockStatic(Bukkit.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(Bukkit.getScheduler()).thenReturn(scheduler);
        // The async save dispatches through the scheduler; running the work
        // inline is what lets the test see what it wrote.
        when(scheduler.runTaskAsynchronously(any(org.bukkit.plugin.Plugin.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    ((Runnable) call.getArgument(1)).run();
                    return null;
                });

        Material material = mock(Material.class);
        when(material.isAir()).thenReturn(false);
        when(material.name()).thenReturn("DIAMOND");
        stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        when(stack.clone()).thenReturn(stack);
        when(stack.isSimilar(any(ItemStack.class))).thenReturn(true);
        when(stack.serializeAsBytes()).thenReturn(new byte[] { 1 });

        registry = new CustomItemRegistry(plugin);
    }

    @AfterEach
    void closeRegistry() {
        if (bukkit != null) {
            bukkit.close();
        }
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    @DisplayName("the synchronous flush at disable time persists a fresh registration")
    void syncFlushPersistsRegistrations() {
        registry.upsert(item(true));

        // This is the call onDisable makes; no periodic save ran first.
        registry.saveToDatabaseSync(manager);
        CustomItemRegistry reloaded = reload();

        assertNotNull(reloaded.getById(ID),
                "an item registered since the last periodic save was lost on stop");
    }

    @Test
    @DisplayName("the synchronous flush writes what the async path writes")
    void syncFlushMatchesTheAsyncSave() {
        registry.upsert(item(true));
        registry.saveToDatabase(manager);

        // A second item arrives after the async save and only the sync flush
        // can write it, so both paths are exercised on distinct rows.
        registry.upsert(item("test:emerald", true));
        registry.saveToDatabaseSync(manager);

        CustomItemRegistry reloaded = reload();

        assertNotNull(reloaded.getById(ID), "the async path stopped persisting");
        assertNotNull(reloaded.getById("test:emerald"),
                "the synchronous flush wrote nothing");
    }

    // --- helpers ------------------------------------------------------------

    private CustomItemRegistry reload() {
        CustomItemRegistry reloaded = new CustomItemRegistry(plugin);
        try (MockedStatic<ItemStack> items = mockStatic(ItemStack.class)) {
            items.when(() -> ItemStack.deserializeBytes(any())).thenReturn(stack);
            reloaded.loadFromDatabase(manager);
        }
        return reloaded;
    }

    private CustomMarketItem item(boolean enabled) {
        return item(ID, enabled);
    }

    private CustomMarketItem item(String canonicalId, boolean enabled) {
        return new CustomMarketItem.Builder()
                .canonicalId(canonicalId)
                .itemStack(stack)
                .sourcePlugin("test")
                .displayName("Ruby")
                .category("CUSTOM_ITEMS")
                .buyPrice(BUY)
                .sellPrice(SELL)
                .enabled(enabled)
                .build();
    }
}

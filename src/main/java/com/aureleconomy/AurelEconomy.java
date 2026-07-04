package com.aureleconomy;

import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.UnifiedItemScanner;
import com.aureleconomy.scanner.ItemDiscoveryListener;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.VaultEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import com.aureleconomy.utils.VersionChecker;

/**
 * Main plugin class for AurelEconomy.
 * Refactored to eliminate magic variables and improve infrastructure modularity.
 */
public class AurelEconomy extends JavaPlugin {

    // Config Key Constants
    public static final String CONF_LAST_VERSION = "last-version";
    public static final String CONF_DB_ENABLED = "web.enabled";
    public static final String CONF_WEB_MODE = "web.mode";
    public static final String CONF_DEFAULT_CURRENCY = "economy.default-currency";

    // Default Values
    private static final String WEB_MODE_CLOUD = "cloud";
    private static final String WEB_MODE_LOCAL = "local";

    private static AurelEconomy instance;
    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private com.aureleconomy.market.MarketManager marketManager;
    private com.aureleconomy.auction.AuctionManager auctionManager;
    private com.aureleconomy.utils.ChatPromptManager chatPromptManager;
    private com.aureleconomy.orders.OrderManager orderManager;
    private VaultEconomy vaultEconomy;
    private com.aureleconomy.web.WebServer webServer;
    private com.aureleconomy.web.CloudSyncManager cloudSync;
    private CustomItemRegistry customItemRegistry;
    private UnifiedItemScanner unifiedScanner;

    private final java.util.Set<org.bukkit.entity.Player> activeViewers = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        instance = this;

        // Auto-Install Vault if missing
        com.aureleconomy.utils.VaultInstaller.install(this);

        // Save/Update config with latest keys and comments
        upgradeConfig();

        // Initialize Database
        databaseManager = new DatabaseManager(this);

        // Version-based Database Backup
        String currentVersion = getPluginMeta().getVersion();
        String lastVersion = getConfig().getString(CONF_LAST_VERSION, "unknown");
        if (!currentVersion.equals(lastVersion)) {
            databaseManager.backupDatabase(lastVersion);
            getConfig().set(CONF_LAST_VERSION, currentVersion);
            saveConfig();
        }

        if (!databaseManager.initialize()) {
            getComponentLogger().error("Failed to initialize database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Managers

        // Check for updates
        VersionChecker.checkVersion(this);

        economyManager = new EconomyManager(this);
        marketManager = new com.aureleconomy.market.MarketManager(this);
        auctionManager = new com.aureleconomy.auction.AuctionManager(this);
        chatPromptManager = new com.aureleconomy.utils.ChatPromptManager(this);
        orderManager = new com.aureleconomy.orders.OrderManager(this);
        orderManager.loadOrders();

        // Initialize custom item system
        if (getConfig().getBoolean("custom-items.enabled", true)) {
            this.customItemRegistry = new CustomItemRegistry(this);
            this.unifiedScanner = new UnifiedItemScanner(this, customItemRegistry);

            // Phase 1: Load previously discovered items from database
            customItemRegistry.loadFromDatabase(databaseManager);
            customItemRegistry.loadConfigOverrides();

            // Phase 2: Plugin API scan (delayed 1s for other plugins to load)
            boolean scanOnStartup = getConfig().getBoolean("custom-items.scan-on-startup", true);
            if (scanOnStartup) {
                getServer().getScheduler().runTaskLater(this, () -> {
                    if (unifiedScanner != null) {
                        unifiedScanner.scanAllPluginAPIs();
                        unifiedScanner.scanPlayerInventories();
                        getLogger().info("[CustomItems] Scan complete: " + customItemRegistry.getTotalItems()
                                + " unique items, " + customItemRegistry.getDuplicatesPrevented() + " duplicates prevented");
                    }
                }, 20L);
            } else {
                getLogger().info("[CustomItems] Startup scan disabled by config (custom-items.scan-on-startup=false)");
            }

            // Phase 3: Register runtime detection listeners
            getServer().getPluginManager().registerEvents(new ItemDiscoveryListener(this, unifiedScanner), this);

            // Phase 4: Periodic rescan
            int scanInterval = getConfig().getInt("custom-items.scan-interval-minutes", 10);
            new org.bukkit.scheduler.BukkitRunnable() {
                @Override
                public void run() {
                    if (unifiedScanner != null) {
                        unifiedScanner.scanPlayerInventories();
                        customItemRegistry.saveToDatabase(databaseManager);
                    }
                }
            }.runTaskTimer(this, 20L * 60L * scanInterval, 20L * 60L * scanInterval);
        }

        // Register Vault Hook
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            vaultEconomy = new VaultEconomy(this, economyManager);
            getServer().getServicesManager().register(Economy.class, vaultEconomy, this, ServicePriority.Highest);
            getComponentLogger().info("Vault hooked successfully!");
        } else {
            getComponentLogger().warn("Vault not found! Other plugins may not be able to use AurelEconomy.");
        }

        // Register Commands
        registerCommands();

        // Register Listeners
        getServer().getPluginManager().registerEvents(new com.aureleconomy.listeners.GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new com.aureleconomy.listeners.SpawnerListener(this), this);
        getServer().getPluginManager().registerEvents(new com.aureleconomy.listeners.JoinListener(this), this);

        // Live GUI Update Task (20 ticks = 1 second)
        startGuiUpdateTask();

        // Periodic Market Price Persistence (Every 5 minutes)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (marketManager != null) marketManager.persistPrices();
            if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);
        }, 6000L, 6000L);

        getComponentLogger().info("AurelEconomy has been enabled!");

        // Check for version updates (async, logs to console)
        com.aureleconomy.utils.VersionChecker.checkVersion(this);

        // Start Web Services
        initializeWebServices();
    }

    private void registerCommands() {
        getCommand("bal").setExecutor(new com.aureleconomy.commands.EconomyCommand(this));
        getCommand("pay").setExecutor(new com.aureleconomy.commands.EconomyCommand(this));
        getCommand("eco").setExecutor(new com.aureleconomy.commands.EconomyCommand(this));
        getCommand("market").setExecutor(new com.aureleconomy.commands.MarketCommand(this));
        getCommand("ah").setExecutor(new com.aureleconomy.commands.AuctionCommand(this));
        getCommand("sell").setExecutor(new com.aureleconomy.commands.SellCommand(this));
        getCommand("stocks").setExecutor(new com.aureleconomy.commands.StocksCommand(this));

        com.aureleconomy.commands.OrdersCommand ordersCmd = new com.aureleconomy.commands.OrdersCommand(this);
        getCommand("orders").setExecutor(ordersCmd);
        getCommand("orders").setTabCompleter(ordersCmd);

        // Custom Items command
        com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
        if (getCommand("customitems") != null) {
            getCommand("customitems").setExecutor(customItemsCmd);
            getCommand("customitems").setTabCompleter(customItemsCmd);
        }

        if (getCommand("web") != null) {
            getCommand("web").setExecutor(new com.aureleconomy.commands.WebCommand(this));
        }
    }

    private void startGuiUpdateTask() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (activeViewers.isEmpty()) return;

            for (org.bukkit.entity.Player p : activeViewers) {
                if (!p.isOnline()) {
                    activeViewers.remove(p);
                    continue;
                }

                org.bukkit.inventory.InventoryView view = p.getOpenInventory();
                Object holder = view.getTopInventory().getHolder();

                if (holder instanceof com.aureleconomy.gui.StocksGUI gui) gui.refresh();
                else if (holder instanceof com.aureleconomy.gui.MarketGUI gui) gui.refresh();
                else if (holder instanceof com.aureleconomy.gui.AuctionGUI gui) gui.refresh();
                else if (holder instanceof com.aureleconomy.gui.OrderMaterialGUI gui) gui.refresh();
                else if (holder instanceof com.aureleconomy.gui.OrdersGUI gui) gui.refresh();
                else if (holder instanceof com.aureleconomy.gui.CustomItemsGUI gui) gui.setupItems();
                else if (holder == null) activeViewers.remove(p);
            }
        }, 20L, 20L);
    }

    private void initializeWebServices() {
        if (getConfig().getBoolean(CONF_DB_ENABLED, false)) {
            String webMode = getConfig().getString(CONF_WEB_MODE, WEB_MODE_CLOUD).toLowerCase();
            if (WEB_MODE_LOCAL.equals(webMode)) {
                webServer = new com.aureleconomy.web.WebServer(this);
                if (!webServer.start()) webServer = null;
            } else {
                cloudSync = new com.aureleconomy.web.CloudSyncManager(this);
                cloudSync.start();
            }
        }
    }

    @Override
    public void onDisable() {
        // Stop web services and clean up sessions
        if (webServer != null) webServer.stop();
        if (cloudSync != null) cloudSync.stop();
        if (marketManager != null) marketManager.persistPrices();
        if (databaseManager != null) databaseManager.close();
        getComponentLogger().info("AurelEconomy has been disabled!");
    }

    public static AurelEconomy getInstance() { return instance; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public EconomyManager getEconomyManager() { return economyManager; }
    public com.aureleconomy.market.MarketManager getMarketManager() { return marketManager; }
    public com.aureleconomy.auction.AuctionManager getAuctionManager() { return auctionManager; }
    public com.aureleconomy.utils.ChatPromptManager getChatPromptManager() { return chatPromptManager; }
    public com.aureleconomy.orders.OrderManager getOrderManager() { return orderManager; }
    public void addViewer(org.bukkit.entity.Player player) { activeViewers.add(player); }
    public void removeViewer(org.bukkit.entity.Player player) { activeViewers.remove(player); }
    public com.aureleconomy.web.WebServer getWebServer() { return webServer; }
    public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }
    public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }
    public com.aureleconomy.web.CloudSyncManager getCloudSync() { return cloudSync; }

    /**
     * Migrate config.yml to the latest default format while preserving
     * ALL user-modified values — including nested sections.
     *
     * Previous implementation only copied leaf values, which broke
     * nested sections like economy.currencies, web.cloud, etc.
     * This version deep-merges the old config into the new defaults,
     * preserving user values at every nesting level.
     *
     * config-version is always updated to the new default since it
     * tracks which config schema the plugin expects.
     */
    private void upgradeConfig() {
        java.io.File configFile = new java.io.File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveDefaultConfig();
            return;
        }

        org.bukkit.configuration.file.YamlConfiguration oldConfig =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(configFile);

        int oldVersion = oldConfig.getInt("config-version", 0);

        // Deep-copy all user values (including nested sections)
        java.util.Map<String, Object> userValues = deepExtract(oldConfig);

        configFile.delete();
        saveDefaultConfig();
        reloadConfig();

        // Deep-merge user values into the new defaults
        for (java.util.Map.Entry<String, Object> entry : userValues.entrySet()) {
            // Always use the new default config-version — it tracks the schema version
            if (entry.getKey().equals("config-version")) continue;
            deepSet(getConfig(), entry.getKey(), entry.getValue());
        }
        saveConfig();
        getComponentLogger().info("config.yml updated from version " + oldVersion
                + " to version " + getConfig().getInt("config-version", 0));
    }

    /**
     * Recursively extract all key-value pairs from a ConfigurationSection,
     * including nested sections as Maps. This preserves the full tree
     * structure rather than flattening to leaf-only values.
     */
    private java.util.Map<String, Object> deepExtract(org.bukkit.configuration.ConfigurationSection section) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            if (value instanceof org.bukkit.configuration.ConfigurationSection sub) {
                result.put(key, deepExtract(sub));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * Deep-merge a value into the config at the given dotted key path.
     * If the value is a Map (from a nested section), recursively merge
     * instead of overwriting the entire section.
     */
    @SuppressWarnings("unchecked")
    private void deepSet(org.bukkit.configuration.ConfigurationSection config, String key, Object value) {
        if (value instanceof java.util.Map) {
            // Nested section: merge into existing section rather than replacing it
            org.bukkit.configuration.ConfigurationSection existing = config.isConfigurationSection(key)
                    ? config.getConfigurationSection(key) : config.createSection(key);
            for (java.util.Map.Entry<String, Object> entry : ((java.util.Map<String, Object>) value).entrySet()) {
                deepSet(existing, entry.getKey(), entry.getValue());
            }
        } else {
            config.set(key, value);
        }
    }
}

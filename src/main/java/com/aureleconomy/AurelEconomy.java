package com.aureleconomy;

import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.economy.EconomyManager;
import com.aureleconomy.economy.VaultEconomy;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public class AurelEconomy extends JavaPlugin {

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
    private final java.util.Set<org.bukkit.entity.Player> activeViewers = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        instance = this;

        // Auto-Install Vault if missing
        com.aureleconomy.utils.VaultInstaller.install(this);

        // Save/Update config — auto-merges new keys from JAR while preserving user
        // values
        upgradeConfig();

        // Initialize Database
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getComponentLogger().error("Failed to initialize database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Initialize Managers
        economyManager = new EconomyManager(this);
        marketManager = new com.aureleconomy.market.MarketManager(this);
        auctionManager = new com.aureleconomy.auction.AuctionManager(this);
        chatPromptManager = new com.aureleconomy.utils.ChatPromptManager(this);
        orderManager = new com.aureleconomy.orders.OrderManager(this);
        orderManager.loadOrders();

        // Register Vault
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            vaultEconomy = new VaultEconomy(this, economyManager);
            getServer().getServicesManager().register(Economy.class, vaultEconomy, this, ServicePriority.Highest);
            getComponentLogger().info("Vault hooked successfully!");
        } else {
            getComponentLogger().warn("Vault not found! Other plugins may not be able to use AurelEconomy.");
        }

        // Register Commands and Listeners
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

        // Register /web command
        if (getCommand("web") != null) {
            getCommand("web").setExecutor(new com.aureleconomy.commands.WebCommand(this));
        }

        getServer().getPluginManager().registerEvents(new com.aureleconomy.listeners.GUIListener(this), this);
        getServer().getPluginManager().registerEvents(new com.aureleconomy.listeners.SpawnerListener(this), this);
        getServer().getPluginManager().registerEvents(new com.aureleconomy.listeners.JoinListener(this), this);

        // Live GUI Updates (Every 1 second / 20 ticks)
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (activeViewers.isEmpty())
                return;

            for (org.bukkit.entity.Player p : activeViewers) {
                if (!p.isOnline()) {
                    activeViewers.remove(p);
                    continue;
                }
                org.bukkit.inventory.InventoryView view = p.getOpenInventory();
                if (view.getTopInventory() != null && view.getTopInventory().getHolder() != null) {
                    if (view.getTopInventory().getHolder() instanceof com.aureleconomy.gui.StocksGUI gui) {
                        gui.refresh();
                    } else if (view.getTopInventory().getHolder() instanceof com.aureleconomy.gui.MarketGUI gui) {
                        gui.refresh();
                    } else if (view.getTopInventory().getHolder() instanceof com.aureleconomy.gui.AuctionGUI gui) {
                        gui.refresh();
                    } else if (view.getTopInventory()
                            .getHolder() instanceof com.aureleconomy.gui.OrderMaterialGUI gui) {
                        gui.refresh();
                    } else if (view.getTopInventory().getHolder() instanceof com.aureleconomy.gui.OrdersGUI gui) {
                        gui.refresh();
                    }
                } else {
                    activeViewers.remove(p);
                }
            }
        }, 20L, 20L);

        // Periodic Market Price Persistence (Every 5 minutes)
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (marketManager != null) {
                marketManager.persistPrices();
            }
        }, 6000L, 6000L);

        getComponentLogger().info("AurelEconomy has been enabled!");

        // Start web dashboard if enabled
        if (getConfig().getBoolean("web.enabled", false)) {
            String webMode = getConfig().getString("web.mode", "cloud").toLowerCase();
            if ("local".equals(webMode)) {
                webServer = new com.aureleconomy.web.WebServer(this);
                if (!webServer.start()) {
                    webServer = null;
                }
            } else {
                // Cloud mode — connect to external Render server
                cloudSync = new com.aureleconomy.web.CloudSyncManager(this);
                cloudSync.start();
            }
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (cloudSync != null) {
            cloudSync.stop();
        }
        if (marketManager != null) {
            marketManager.persistPrices();
        }
        if (databaseManager != null) {
            databaseManager.close();
        }
        getComponentLogger().info("AurelEconomy has been disabled!");
    }

    public static AurelEconomy getInstance() {
        return instance;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public EconomyManager getEconomyManager() {
        return economyManager;
    }

    public com.aureleconomy.market.MarketManager getMarketManager() {
        return marketManager;
    }

    public com.aureleconomy.auction.AuctionManager getAuctionManager() {
        return auctionManager;
    }

    public com.aureleconomy.utils.ChatPromptManager getChatPromptManager() {
        return chatPromptManager;
    }

    public com.aureleconomy.orders.OrderManager getOrderManager() {
        return orderManager;
    }

    public void addViewer(org.bukkit.entity.Player player) {
        activeViewers.add(player);
    }

    public void removeViewer(org.bukkit.entity.Player player) {
        activeViewers.remove(player);
    }

    public com.aureleconomy.web.WebServer getWebServer() {
        return webServer;
    }

    public com.aureleconomy.web.CloudSyncManager getCloudSync() {
        return cloudSync;
    }

    /**
     * Upgrades config.yml on every startup:
     * 1. Load the user's existing config values
     * 2. Delete the old config file
     * 3. Save the fresh default config from the JAR (with full comments)
     * 4. Re-apply every value the user had set
     * 5. Save to disk
     *
     * Result: the config always has the latest comments and new sections
     * from the JAR, but the user's custom values are never lost.
     */
    private void upgradeConfig() {
        java.io.File configFile = new java.io.File(getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            // First run — just save the default
            saveDefaultConfig();
            return;
        }

        // 1. Snapshot every key the user currently has
        org.bukkit.configuration.file.YamlConfiguration oldConfig = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(configFile);
        java.util.Map<String, Object> userValues = new java.util.LinkedHashMap<>();
        for (String key : oldConfig.getKeys(true)) {
            if (!oldConfig.isConfigurationSection(key)) {
                userValues.put(key, oldConfig.get(key));
            }
        }

        // 2. Delete old file
        configFile.delete();

        // 3. Save fresh default from JAR (contains all new keys + comments)
        saveDefaultConfig();
        reloadConfig();

        // 4. Re-apply the user's values (never overwrite with defaults)
        for (java.util.Map.Entry<String, Object> entry : userValues.entrySet()) {
            // Skip config-version — always use the latest from the JAR
            if (entry.getKey().equals("config-version"))
                continue;
            getConfig().set(entry.getKey(), entry.getValue());
        }

        // 5. Persist
        saveConfig();
        getComponentLogger().info("config.yml updated to version "
                + getConfig().getInt("config-version", 0) + " — your settings have been preserved.");
    }
}

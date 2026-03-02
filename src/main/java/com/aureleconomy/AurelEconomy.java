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
    private final java.util.Set<org.bukkit.entity.Player> activeViewers = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    @Override
    public void onEnable() {
        instance = this;

        // Auto-Install Vault if missing
        com.aureleconomy.utils.VaultInstaller.install(this);

        // Save/Update config
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

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
    }

    @Override
    public void onDisable() {
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
}

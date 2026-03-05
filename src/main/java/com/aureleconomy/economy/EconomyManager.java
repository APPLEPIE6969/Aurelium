package com.aureleconomy.economy;

import com.aureleconomy.AurelEconomy;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class EconomyManager {

    private final AurelEconomy plugin;
    // Cache: Map<UUID, Map<CurrencyName, Balance>>
    private final Map<UUID, Map<String, Double>> balanceCache = new ConcurrentHashMap<>();

    public EconomyManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    public String getDefaultCurrency() {
        return plugin.getConfig().getString("economy.default-currency", "Aurels");
    }

    public double getBalance(OfflinePlayer player) {
        return getBalance(player, getDefaultCurrency());
    }

    public double getBalance(OfflinePlayer player, String currency) {
        UUID uuid = player.getUniqueId();
        if (balanceCache.containsKey(uuid) && balanceCache.get(uuid).containsKey(currency)) {
            return balanceCache.get(uuid).get(currency);
        }

        // Load from DB if not in cache
        return loadBalance(uuid, currency);
    }

    public void setBalance(OfflinePlayer player, double amount) {
        setBalance(player, amount, getDefaultCurrency());
    }

    public void setBalance(OfflinePlayer player, double amount, String currency) {
        UUID uuid = player.getUniqueId();
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, amount);

        saveBalance(uuid, currency, amount);

        // Ensure name is updated in players table when a balance changes
        String name = player.getName() != null ? player.getName() : player.getUniqueId().toString();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO players (uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setString(3, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                // Ignore silent update errors
            }
        });
    }

    public void deposit(OfflinePlayer player, double amount) {
        deposit(player, amount, getDefaultCurrency());
    }

    public void deposit(OfflinePlayer player, double amount, String currency) {
        setBalance(player, getBalance(player, currency) + amount, currency);
    }

    public void withdraw(OfflinePlayer player, double amount) {
        withdraw(player, amount, getDefaultCurrency());
    }

    public void withdraw(OfflinePlayer player, double amount, String currency) {
        setBalance(player, getBalance(player, currency) - amount, currency);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return has(player, amount, getDefaultCurrency());
    }

    public boolean has(OfflinePlayer player, double amount, String currency) {
        return getBalance(player, currency) >= amount;
    }

    public String format(double amount) {
        return format(amount, getDefaultCurrency());
    }

    public String getCurrencySymbol(String currency) {
        String path = "economy.currencies." + currency + ".symbol";
        return plugin.getConfig().getString(path,
                plugin.getConfig().getString("economy.currency-symbol", "₳"));
    }

    public String format(double amount, String currency) {
        return getCurrencySymbol(currency) + String.format("%.2f", amount);
    }

    private double loadBalance(UUID uuid, String currency) {
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                .prepareStatement("SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                double bal = rs.getDouble("balance");
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, bal);
                return bal;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Default balance
        double startBal = plugin.getConfig().getDouble("economy.currencies." + currency + ".starting-balance",
                plugin.getConfig().getDouble("economy.starting-balance", 100.0));
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, startBal);
        return startBal;
    }

    public void saveBalance(UUID uuid, String currency, double amount) {
        // Run async to avoid lag
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) " +
                            "ON CONFLICT(uuid, currency) DO UPDATE SET balance = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, currency);
                ps.setDouble(3, amount);
                ps.setDouble(4, amount);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager", e);
            }
        });
    }
}

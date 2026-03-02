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
    private final Map<UUID, Double> balanceCache = new ConcurrentHashMap<>();

    public EconomyManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    public double getBalance(OfflinePlayer player) {
        if (balanceCache.containsKey(player.getUniqueId())) {
            return balanceCache.get(player.getUniqueId());
        }

        // Load from DB if not in cache
        return loadBalance(player.getUniqueId());
    }

    public void setBalance(OfflinePlayer player, double amount) {
        balanceCache.put(player.getUniqueId(), amount);
        String name = player.getName() != null ? player.getName() : player.getUniqueId().toString();
        saveBalance(player.getUniqueId(), name, amount);
    }

    public void deposit(OfflinePlayer player, double amount) {
        setBalance(player, getBalance(player) + amount);
    }

    public void withdraw(OfflinePlayer player, double amount) {
        setBalance(player, getBalance(player) - amount);
    }

    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    public String format(double amount) {
        String symbol = plugin.getConfig().getString("economy.currency-symbol", "₳");
        return symbol + String.format("%.2f", amount);
    }

    private double loadBalance(UUID uuid) {
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                .prepareStatement("SELECT balance FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                double bal = rs.getDouble("balance");
                balanceCache.put(uuid, bal);
                return bal;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // Default balance
        double startBal = plugin.getConfig().getDouble("economy.starting-balance", 100.0);
        balanceCache.put(uuid, startBal);
        return startBal;
    }

    public void saveBalance(UUID uuid, String name, double amount) {
        // Run async to avoid lag
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO players (uuid, name, balance) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = ?, name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setDouble(3, amount);
                ps.setDouble(4, amount);
                ps.setString(5, name);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager", e);
            }
        });
    }
}

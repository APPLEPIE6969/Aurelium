package com.aureleconomy.economy;

import com.aureleconomy.AurelEconomy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * Manages player balances with support for multiple currencies.
 * Refactored to use BigDecimal for guaranteed mathematical precision.
 */
public class EconomyManager {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    private static final double DEFAULT_STARTING_BALANCE = 100.0;

    private final AurelEconomy plugin;
    // Cache: Map<UUID, Map<CurrencyName, Balance>>
    private final Map<UUID, Map<String, BigDecimal>> balanceCache = new ConcurrentHashMap<>();

    public EconomyManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    public String getDefaultCurrency() {
        return plugin.getConfig().getString("economy.default-currency", "Aurels");
    }

    public BigDecimal getBalance(OfflinePlayer player) {
        return getBalance(player, getDefaultCurrency());
    }

    public BigDecimal getBalance(OfflinePlayer player, String currency) {
        UUID uuid = player.getUniqueId();
        Map<String, BigDecimal> userBalances = balanceCache.get(uuid);
        if (userBalances != null && userBalances.containsKey(currency)) {
            return userBalances.get(currency);
        }

        // Load from DB if not in cache
        return loadBalance(uuid, currency);
    }

    public void setBalance(OfflinePlayer player, BigDecimal amount) {
        setBalance(player, amount, getDefaultCurrency());
    }

    public void deposit(OfflinePlayer player, BigDecimal amount) {
        deposit(player, amount, getDefaultCurrency());
    }

    public synchronized void deposit(OfflinePlayer player, BigDecimal amount, String currency) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;
        
        UUID uuid = player.getUniqueId();
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);

        // Update Database Atomically
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) " +
                            "ON CONFLICT(uuid, currency) DO UPDATE SET balance = balance + ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, currency);
                ps.setBigDecimal(3, normalizedAmount);
                ps.setBigDecimal(4, normalizedAmount);
                ps.executeUpdate();
                
                // Refresh local cache after successful atomic update
                loadBalance(uuid, currency);
                
                // Update player name metadata
                updatePlayerMetadata(player);
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager while depositing", e);
            }
        });

        // Update Cache Optimistically (Main Thread)
        BigDecimal current = getBalance(player, currency);
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, current.add(normalizedAmount));
    }

    public void withdraw(OfflinePlayer player, BigDecimal amount) {
        withdraw(player, amount, getDefaultCurrency());
    }

    public synchronized void withdraw(OfflinePlayer player, BigDecimal amount, String currency) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) return;

        UUID uuid = player.getUniqueId();
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);

        // Update Database Atomically with check
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "UPDATE player_balances SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?")) {
                ps.setBigDecimal(1, normalizedAmount);
                ps.setString(2, uuid.toString());
                ps.setString(3, currency);
                ps.setBigDecimal(4, normalizedAmount);
                
                int affectedRows = ps.executeUpdate();
                if (affectedRows == 0) {
                    // This could be a race condition where balance dropped below required amount
                    // Re-force sync cache
                    loadBalance(uuid, currency);
                } else {
                    // Update player name metadata
                    updatePlayerMetadata(player);
                }
                
                // Refresh local cache
                loadBalance(uuid, currency);
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager while withdrawing", e);
            }
        });

        // Update Cache Optimistically (Main Thread)
        BigDecimal current = getBalance(player, currency);
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, current.subtract(normalizedAmount).max(BigDecimal.ZERO));
    }

    private void updatePlayerMetadata(OfflinePlayer player) {
        String name = player.getName();
        if (name == null) return;
        UUID uuid = player.getUniqueId();

        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                "INSERT INTO players (uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Optional metadata
        }
    }

    public boolean has(OfflinePlayer player, BigDecimal amount) {
        return has(player, amount, getDefaultCurrency());
    }

    public boolean has(OfflinePlayer player, BigDecimal amount, String currency) {
        return getBalance(player, currency).compareTo(amount) >= 0;
    }

    public String format(BigDecimal amount) {
        return format(amount, getDefaultCurrency());
    }

    public String getCurrencySymbol(String currency) {
        String path = "economy.currencies." + currency + ".symbol";
        return plugin.getConfig().getString(path,
                plugin.getConfig().getString("economy.currency-symbol", "₳"));
    }

    public String format(BigDecimal amount, String currency) {
        return amount.setScale(SCALE, ROUNDING_MODE).toPlainString();
    }

    public String getFormattedWithSymbol(BigDecimal amount, String currency) {
        return getCurrencySymbol(currency) + format(amount, currency);
    }

    private BigDecimal loadBalance(UUID uuid, String currency) {
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                .prepareStatement("SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                BigDecimal bal = rs.getBigDecimal("balance");
                if (bal == null) bal = BigDecimal.ZERO;
                bal = bal.setScale(SCALE, ROUNDING_MODE);
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, bal);
                return bal;
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Database error while loading balance for " + uuid, e);
        }

        // Default balance
        double startBalRaw = plugin.getConfig().getDouble("economy.currencies." + currency + ".starting-balance",
                plugin.getConfig().getDouble("economy.starting-balance", DEFAULT_STARTING_BALANCE));
        BigDecimal startBal = BigDecimal.valueOf(startBalRaw).setScale(SCALE, ROUNDING_MODE);
        
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, startBal);
        return startBal;
    }

    /**
     * Set balance directly (Admin/Force use). 
     * For transactions, use deposit/withdraw for atomic safety.
     */
    public void setBalance(OfflinePlayer player, BigDecimal amount, String currency) {
        UUID uuid = player.getUniqueId();
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);
        
        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, normalizedAmount);
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection().prepareStatement(
                    "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) " +
                            "ON CONFLICT(uuid, currency) DO UPDATE SET balance = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, currency);
                ps.setBigDecimal(3, normalizedAmount);
                ps.setBigDecimal(4, normalizedAmount);
                ps.executeUpdate();
                updatePlayerMetadata(player);
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager while saving balance", e);
            }
        });
    }
}

package com.aureleconomy.economy;

import com.aureleconomy.AurelEconomy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class EconomyManager {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_EVEN;
    private static final double DEFAULT_STARTING_BALANCE = 100.0;

    private final AurelEconomy plugin;
    private final Map<UUID, ConcurrentHashMap<String, BigDecimal>> balanceCache = new ConcurrentHashMap<>();
    private String defaultCurrency;

    public EconomyManager(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    // get default currency from config, fall back to first available currency
    // never hardcode "aurels" - if it's deleted from config it stays deleted
    public String getDefaultCurrency() {
        if (this.defaultCurrency == null || !currencyExists(this.defaultCurrency)) {
            this.defaultCurrency = plugin.getConfig().getString("economy.default-currency", "");
            if (!currencyExists(this.defaultCurrency)) {
                var currencies = plugin.getConfig().getConfigurationSection("economy.currencies");
                if (currencies != null && currencies.getKeys(false).size() > 0) {
                    this.defaultCurrency = currencies.getKeys(false).iterator().next();
                }
            }
        }
        return this.defaultCurrency;
    }

    // check if a currency is defined in config
    public boolean currencyExists(String currency) {
        if (currency == null || currency.isEmpty()) return false;
        var currencies = plugin.getConfig().getConfigurationSection("economy.currencies");
        return currencies != null && currencies.contains(currency);
    }

    public BigDecimal getBalance(OfflinePlayer player) {
        return getBalance(player, getDefaultCurrency());
    }

    public BigDecimal getBalance(OfflinePlayer player, String currency) {
        UUID uuid = player.getUniqueId();
        // thread-safe: use concurrenthashmap atomic operations
        ConcurrentHashMap<String, BigDecimal> userBalances = balanceCache.get(uuid);
        if (userBalances != null) {
            BigDecimal cached = userBalances.get(currency);
            if (cached != null) {
                return cached;
            }
        }

        return loadBalance(uuid, currency);
    }

    public void setBalance(OfflinePlayer player, BigDecimal amount) {
        setBalance(player, amount, getDefaultCurrency());
    }

    public void deposit(OfflinePlayer player, BigDecimal amount) {
        deposit(player, amount, getDefaultCurrency());
    }

    /**
     * Credits {@code amount} and reports whether the database accepted it.
     *
     * <p>The result is the row count of the write itself, not the cache and not an
     * intention to write later. A caller that is about to hand something over has
     * to check it.
     */
    public boolean deposit(OfflinePlayer player, BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            return false;

        UUID uuid = player.getUniqueId();
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);

        boolean credited;
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement(
                    plugin.getDatabaseManager().isMySQL()
                            ? "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) AS new ON DUPLICATE KEY UPDATE player_balances.balance = player_balances.balance + new.balance"
                            : "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET balance = balance + ?")) {

                ps.setString(1, uuid.toString());
                ps.setString(2, currency);
                ps.setBigDecimal(3, normalizedAmount);
                if (!plugin.getDatabaseManager().isMySQL()) {
                    ps.setBigDecimal(4, normalizedAmount);
                }
                // An upsert that really changed the value always reports at least one
                // row: MySQL gives 2 for an update and 1 for an insert, SQLite gives 1
                // for both. Anything below 1 means nothing reached the database.
                credited = ps.executeUpdate() >= 1;
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager while depositing", e);
                credited = false;
            }

            // Deliberately after the borrow, not inside it: this method and
            // loadBalance() both borrow their own connection, and the SQLite pool
            // holds exactly one. Stacking borrows would time out instead of
            // finishing. The write lock still spans all three, so no other writer
            // can interleave.
            if (credited) {
                updatePlayerMetadata(player);
            }
            // Refreshed either way, so a refused write cannot leave the cache
            // claiming money that was never credited.
            loadBalance(uuid, currency);
        }
        return credited;
    }

    public void withdraw(OfflinePlayer player, BigDecimal amount) {
        withdraw(player, amount, getDefaultCurrency());
    }

    /**
     * Debits {@code amount} and reports whether the database accepted it.
     *
     * <p>See {@link #deposit(OfflinePlayer, BigDecimal, String)}. This used to
     * decide affordability from the cache, write asynchronously, and return void,
     * so a caller could not tell a real debit from a refused one.
     */
    public boolean withdraw(OfflinePlayer player, BigDecimal amount, String currency) {
        return debit(player, amount, currency, "withdraw");
    }

    /**
     * The same debit, kept because callers use this name to mean "refuse rather
     * than go negative".
     *
     * <p>It no longer behaves differently from {@link #withdraw}: the guarded
     * UPDATE is the only authority on affordability, so both refuse exactly when
     * the database says the player cannot pay.
     */
    public boolean withdrawIfSufficient(OfflinePlayer player, BigDecimal amount, String currency) {
        return debit(player, amount, currency, "withdrawIfSufficient");
    }

    private boolean debit(OfflinePlayer player, BigDecimal amount, String currency, String operation) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            return false;

        UUID uuid = player.getUniqueId();
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);

        boolean debited;
        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement(
                    "UPDATE player_balances SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?")) {
                ps.setBigDecimal(1, normalizedAmount);
                ps.setString(2, uuid.toString());
                ps.setString(3, currency);
                ps.setBigDecimal(4, normalizedAmount);

                // The only authority. The cache is deliberately not consulted to
                // decide whether the player can afford this, because a cache that
                // disagrees with the database is precisely the case that used to
                // report a withdrawal that never happened.
                debited = ps.executeUpdate() >= 1;
            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in EconomyManager while " + operation, e);
                debited = false;
            }

            // After the borrow, not inside it, for the same reason as deposit():
            // both helpers borrow a connection of their own and the SQLite pool
            // holds one. The write lock still spans all three.
            if (debited) {
                updatePlayerMetadata(player);
            }

            // Refreshed either way, so a refused write cannot leave the cache
            // claiming money that never moved. Before the warning, so the balance
            // in the warning is the database's answer rather than the stale value
            // the request happened to be judged against.
            loadBalance(uuid, currency);

            if (!debited) {
                // The phrase "Insufficient funds for withdraw" is a contract. The
                // RCON suite greps the server log for it to prove an operator can
                // see why a withdrawal was refused, and "withdrawIfSufficient"
                // contains it as a prefix, so both paths are covered.
                plugin.getComponentLogger().warn("Insufficient funds for " + operation + ": "
                        + player.getName() + " tried to withdraw " + normalizedAmount
                        + " " + currency + " but the database holds "
                        + getBalanceFromCache(uuid, currency));
            }
        }
        return debited;
    }

    /**
     * Moves {@code amount} from one player to another, or moves nothing at all.
     *
     * <p>Both legs run in a single transaction on the shared connection while
     * holding the write lock, so no other writer can interleave between them and a
     * failure on either leg rolls the other back. A transfer to yourself is a
     * successful no-op, because there is nothing to lose.
     *
     * <p>A caller that is about to hand something over has to check the result.
     */
    public boolean transfer(OfflinePlayer from, OfflinePlayer to, BigDecimal amount, String currency) {
        if (from == null || to == null)
            return false;
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            return false;

        UUID fromId = from.getUniqueId();
        UUID toId = to.getUniqueId();
        if (fromId.equals(toId))
            return true;

        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);
        boolean committed = false;

        synchronized (plugin.getDatabaseManager().getWriteLock()) {
            // Borrowed inside the write lock and released as soon as the
            // transaction resolves, so the connection is not held across the
            // metadata write and the two balance reloads below.
            try (Connection connection = plugin.getDatabaseManager().getConnection()) {
                boolean previousAutoCommit = true;
                try {
                    previousAutoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);

                    if (debitRow(connection, fromId, currency, normalizedAmount) >= 1
                            && creditRow(connection, toId, currency, normalizedAmount) >= 1) {
                        connection.commit();
                        committed = true;
                    } else {
                        // Either the payer could not cover it or the payee could not be
                        // credited. Nothing may be left half done.
                        connection.rollback();
                    }
                } catch (SQLException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        plugin.getComponentLogger().error("Could not roll back a failed transfer", rollbackFailure);
                    }
                    plugin.getComponentLogger().error("Database error in EconomyManager while transferring", e);
                } finally {
            }
                try {
                    connection.setAutoCommit(previousAutoCommit);
                } catch (SQLException restoreFailure) {
                    plugin.getComponentLogger()
                            .error("Could not restore autocommit after a transfer", restoreFailure);
                }
            } catch (SQLException e) {
                // Reaching here means the borrow itself failed, or the handle could not
                // be returned. Neither leg ran, so nothing moved.
                plugin.getComponentLogger().error(
                        "Database error in EconomyManager while borrowing a connection to transfer", e);
            }

            if (committed) {
                updatePlayerMetadata(from);
                updatePlayerMetadata(to);
            }
            // Both sides are refreshed either way, so the cache can never keep
            // showing a balance the database did not agree to.
            loadBalance(fromId, currency);
            loadBalance(toId, currency);
        }
        return committed;
    }

    private int debitRow(Connection connection, UUID uuid, String currency, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_balances SET balance = balance - ? WHERE uuid = ? AND currency = ? AND balance >= ?")) {
            ps.setBigDecimal(1, amount);
            ps.setString(2, uuid.toString());
            ps.setString(3, currency);
            ps.setBigDecimal(4, amount);
            return ps.executeUpdate();
        }
    }

    private int creditRow(Connection connection, UUID uuid, String currency, BigDecimal amount)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                plugin.getDatabaseManager().isMySQL()
                        ? "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) AS new ON DUPLICATE KEY UPDATE player_balances.balance = player_balances.balance + new.balance"
                        : "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET balance = balance + ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency);
            ps.setBigDecimal(3, amount);
            if (!plugin.getDatabaseManager().isMySQL()) {
                ps.setBigDecimal(4, amount);
            }
            return ps.executeUpdate();
        }
    }

    private void updatePlayerMetadata(OfflinePlayer player) {
        String name = player.getName();
        if (name == null)
            return;
        UUID uuid = player.getUniqueId();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement(
                plugin.getDatabaseManager().isMySQL()
                        ? "INSERT INTO players (uuid, name) VALUES (?, ?) AS new ON DUPLICATE KEY UPDATE name = new.name"
                        : "INSERT INTO players (uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            if (!plugin.getDatabaseManager().isMySQL()) {
                ps.setString(3, name);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
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

    /**
     * The cached balance only, with no database call, and zero if it is not cached.
     *
     * <p>Not a decision input. Affordability is decided by the guarded UPDATE, so
     * nothing here can talk a caller into believing a withdrawal happened. Its one
     * remaining use is reporting the refreshed value after a refusal, by which
     * point {@link #loadBalance} has already replaced it with the database's.
     */
    private BigDecimal getBalanceFromCache(UUID uuid, String currency) {
        ConcurrentHashMap<String, BigDecimal> userBalances = balanceCache.get(uuid);
        if (userBalances != null) {
            BigDecimal cached = userBalances.get(currency);
            if (cached != null) {
                return cached;
            }
        }
        return BigDecimal.ZERO;
    }

    private void scheduleAsyncWrite(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            task.run();
        }
    }

    private BigDecimal loadBalance(UUID uuid, String currency) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement("SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                BigDecimal bal = rs.getBigDecimal("balance");
                if (bal == null)
                    bal = BigDecimal.ZERO;
                bal = bal.setScale(SCALE, ROUNDING_MODE);
                balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, bal);
                return bal;
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Database error while loading balance for " + uuid, e);
            // C4: a read that failed is not an account that does not exist. Return
            // what is known and write nothing, so the real balance survives a
            // timeout and the next access retries the database.
            return knownBalanceOrZero(uuid, currency);
        }

        return creditStartingBalance(uuid, currency);
    }

    /**
     * What a failed read returns: the last value loaded for this player, or zero.
     *
     * <p>Zero cannot invent money, and it is not cached, so the next access asks the
     * database again instead of trusting a number that came out of an outage.
     */
    private BigDecimal knownBalanceOrZero(UUID uuid, String currency) {
        ConcurrentHashMap<String, BigDecimal> userBalances = balanceCache.get(uuid);
        BigDecimal cached = userBalances != null ? userBalances.get(currency) : null;
        return cached != null ? cached : BigDecimal.ZERO.setScale(SCALE, ROUNDING_MODE);
    }

    /**
     * Writes the starting balance for an account the database confirmed does not
     * exist. Only {@link #loadBalance}'s zero-row path may reach this.
     */
    private BigDecimal creditStartingBalance(UUID uuid, String currency) {
        double startBalRaw = plugin.getConfig().getDouble("economy.currencies." + currency + ".starting-balance",
                plugin.getConfig().getDouble("economy.starting-balance", DEFAULT_STARTING_BALANCE));
        BigDecimal startBal = BigDecimal.valueOf(startBalRaw).setScale(SCALE, ROUNDING_MODE);
        try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement(
                        plugin.getDatabaseManager().isMySQL()
                                ? "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) AS new ON DUPLICATE KEY UPDATE player_balances.balance = new.balance"
                                : "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET balance = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, currency);
            ps.setBigDecimal(3, startBal);
            if (!plugin.getDatabaseManager().isMySQL()) {
                ps.setBigDecimal(4, startBal);
            }
            ps.executeUpdate();
            plugin.getComponentLogger().info("Created initial balance for " + uuid + ": " + startBal + " " + currency);
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Database error while creating initial balance for " + uuid, e);
        }

        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, startBal);
        return startBal;
    }

    public void setBalance(OfflinePlayer player, BigDecimal amount, String currency) {
        UUID uuid = player.getUniqueId();
        BigDecimal normalizedAmount = amount.setScale(SCALE, ROUNDING_MODE);

        balanceCache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(currency, normalizedAmount);

        scheduleAsyncWrite(() -> {
            synchronized (plugin.getDatabaseManager().getWriteLock()) {
                try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement(
                        plugin.getDatabaseManager().isMySQL()
                                ? "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) AS new ON DUPLICATE KEY UPDATE player_balances.balance = new.balance"
                                : "INSERT INTO player_balances (uuid, currency, balance) VALUES (?, ?, ?) ON CONFLICT(uuid, currency) DO UPDATE SET balance = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, currency);
                    ps.setBigDecimal(3, normalizedAmount);
                    if (!plugin.getDatabaseManager().isMySQL()) {
                        ps.setBigDecimal(4, normalizedAmount);
                    }
                    ps.executeUpdate();
                } catch (SQLException e) {
                    plugin.getComponentLogger().error("Database error in EconomyManager while saving balance", e);
                }
                // Out of the borrow: updatePlayerMetadata() borrows its own
                // connection, and the SQLite pool holds one.
                updatePlayerMetadata(player);
            }
        });
    }

    public void invalidateCache(UUID uuid) {
        balanceCache.remove(uuid);
    }
}

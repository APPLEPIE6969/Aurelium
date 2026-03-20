package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EconomyCommand implements CommandExecutor, TabCompleter {

    private final AurelEconomy plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public EconomyCommand(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    private boolean isValidCurrency(String currency) {
        return plugin.getConfig().getConfigurationSection("economy.currencies").contains(currency);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        // /bal [player] [currency]
        if (label.equalsIgnoreCase("bal") || label.equalsIgnoreCase("balance") || label.equalsIgnoreCase("money")) {
            handleBalance(sender, args);
            return true;
        }

        // /pay <player> <amount> [currency]
        if (label.equalsIgnoreCase("pay")) {
            handlePay(sender, args);
            return true;
        }

        // /eco <give|take|set> <player> <amount> [currency]
        if (label.equalsIgnoreCase("eco")) {
            handleEco(sender, args);
            return true;
        }

        return false;
    }

    private void handleBalance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aureleconomy.bal")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }

        String targetName;
        String currencyParam = null;

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Console must specify a player."));
                return;
            }
            targetName = sender.getName();
        } else if (args.length == 1) {
            if (isValidCurrency(args[0])) {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(Component.text("Console must specify a player."));
                    return;
                }
                targetName = sender.getName();
                currencyParam = args[0];
            } else {
                targetName = args[0];
            }
        } else {
            targetName = args[0];
            currencyParam = args[1];
        }

        final String finalCurrency = currencyParam != null ? currencyParam
                : plugin.getEconomyManager().getDefaultCurrency();

        sender.sendMessage(Component.text("Checking balance...", NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            BigDecimal bal = plugin.getEconomyManager().getBalance(target, finalCurrency);
            String prefix = plugin.getConfig().getString("prefix", "<gold>[AurelEconomy] <gray>");

            Bukkit.getScheduler().runTask(plugin, () -> {
                String formatted = plugin.getEconomyManager().format(bal, finalCurrency);
                String symbol = plugin.getEconomyManager().getCurrencySymbol(finalCurrency);
                if (sender instanceof Player p && target.getUniqueId().equals(p.getUniqueId())) {
                    String msg = plugin.getConfig().getString("economy.balance",
                            "Balance (%currency%): %amount%%symbol%");
                    sender.sendMessage(mm.deserialize(prefix + msg
                            .replace("%currency%", finalCurrency)
                            .replace("%amount%", formatted)
                            .replace("%symbol%", symbol)));
                } else {
                    String msg = plugin.getConfig().getString("economy.balance-other",
                            "Balance of %player% (%currency%): %amount%%symbol%");
                    sender.sendMessage(mm.deserialize(prefix + msg
                            .replace("%player%", target.getName() != null ? target.getName() : targetName)
                            .replace("%currency%", finalCurrency)
                            .replace("%amount%", formatted)
                            .replace("%symbol%", symbol)));
                }
            });
        });
    }

    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can pay."));
            return;
        }

        if (!player.hasPermission("aureleconomy.pay")) {
            player.sendMessage(Component.text("You do not have permission to pay other players.", NamedTextColor.RED));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /pay <player> <amount> [currency]"));
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount."));
            return;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            player.sendMessage(Component.text("Amount must be positive.", NamedTextColor.RED));
            return;
        }

        String currency = (args.length >= 3) ? args[2] : plugin.getEconomyManager().getDefaultCurrency();
        if (!isValidCurrency(currency)) {
            player.sendMessage(Component.text("Invalid currency: " + currency, NamedTextColor.RED));
            return;
        }

        String targetName = args[0];
        player.sendMessage(Component.text("Processing payment...", NamedTextColor.GRAY));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

            if (target.getUniqueId().equals(player.getUniqueId())) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> player.sendMessage(Component.text("You cannot pay yourself.", NamedTextColor.RED)));
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.getEconomyManager().has(player, amount, currency)) {
                    plugin.getEconomyManager().withdraw(player, amount, currency);
                    plugin.getEconomyManager().deposit(target, amount, currency);
                    String formatted = plugin.getEconomyManager().format(amount, currency);
                    String symbol = plugin.getEconomyManager().getCurrencySymbol(currency);

                    String payMsg = plugin.getConfig().getString("economy.paid",
                            "<green>You paid <white>%player% <yellow>%amount%%symbol%")
                            .replace("%player%", target.getName() != null ? target.getName() : targetName)
                            .replace("%amount%", formatted)
                            .replace("%symbol%", symbol)
                            .replace("%currency%", currency);
                    player.sendMessage(mm.deserialize(payMsg));

                    if (target.isOnline()) {
                        Player op = target.getPlayer();
                        if (op != null) {
                            String recvMsg = plugin.getConfig().getString("economy.received",
                                    "<green>You received <yellow>%amount%%symbol% <green>from <white>%player%")
                                    .replace("%player%", player.getName())
                                    .replace("%amount%", formatted)
                                    .replace("%symbol%", symbol)
                                    .replace("%currency%", currency);
                            op.sendMessage(mm.deserialize(recvMsg));
                        }
                    }
                } else {
                    player.sendMessage(Component.text("Insufficient funds.", NamedTextColor.RED));
                }
            });
        });
    }

    private void handleEco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aureleconomy.admin")) {
            sender.sendMessage(Component.text("No permission."));
            return;
        }

        if (args.length < 3) { // Requires give|take|set, player, amount
            sender.sendMessage(Component.text("Usage: /eco <give|take|set> <player> <amount> [currency]"));
            return;
        }

        String action = args[0].toLowerCase();
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        BigDecimal amount;

        try {
            amount = new BigDecimal(args[2]);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                sender.sendMessage(Component.text("Amount must be positive.", NamedTextColor.RED));
                return;
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid amount."));
            return;
        }

        String currency = args.length >= 4 ? args[3] : plugin.getEconomyManager().getDefaultCurrency();
        if (!isValidCurrency(currency)) {
            sender.sendMessage(Component.text("Invalid currency: " + currency));
            return;
        }

        String prefix = plugin.getConfig().getString("prefix", "[AurelEconomy] ");

        switch (action) {
            case "give":
                plugin.getEconomyManager().deposit(target, amount, currency);
                sender.sendMessage(mm.deserialize(prefix +
                        plugin.getConfig().getString("economy.admin-give", "Gave %player% %amount% (%currency%)")
                                .replace("%player%", target.getName() != null ? target.getName() : "You")
                                .replace("%currency%", currency)
                                .replace("%amount%", plugin.getEconomyManager().format(amount, currency))
                                .replace("%symbol%", plugin.getEconomyManager().getCurrencySymbol(currency))));
                break;
            case "take":
                plugin.getEconomyManager().withdraw(target, amount, currency);
                sender.sendMessage(mm.deserialize(prefix +
                        plugin.getConfig().getString("economy.admin-take", "Took %amount% (%currency%) from %player%")
                                .replace("%player%", target.getName() != null ? target.getName() : "You")
                                .replace("%currency%", currency)
                                .replace("%amount%", plugin.getEconomyManager().format(amount, currency))
                                .replace("%symbol%", plugin.getEconomyManager().getCurrencySymbol(currency))));
                break;
            case "set":
                plugin.getEconomyManager().setBalance(target, amount, currency);
                sender.sendMessage(mm.deserialize(prefix +
                        plugin.getConfig().getString("economy.admin-set", "Set balance of %player% to %amount% (%currency%)")
                                .replace("%player%", target.getName() != null ? target.getName() : "You")
                                .replace("%currency%", currency)
                                .replace("%amount%", plugin.getEconomyManager().format(amount, currency))
                                .replace("%symbol%", plugin.getEconomyManager().getCurrencySymbol(currency))));
                break;
            default:
                sender.sendMessage(Component.text("Unknown action: " + action));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        List<String> currencies = new ArrayList<>(
                plugin.getConfig().getConfigurationSection("economy.currencies").getKeys(false));

        if (label.equalsIgnoreCase("bal") || label.equalsIgnoreCase("balance") || label.equalsIgnoreCase("money")) {
            if (args.length == 1) {
                List<String> suggestions = new ArrayList<>(currencies);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    suggestions.add(p.getName());
                }
                return suggestions;
            }
            if (args.length == 2)
                return currencies; // player, [currency]
        }

        if (label.equalsIgnoreCase("pay")) {
            if (args.length == 1)
                return null; // online players
            if (args.length == 2)
                return Collections.emptyList(); // amount
            if (args.length == 3)
                return currencies; // [currency]
        }

        if (label.equalsIgnoreCase("eco")) {
            if (args.length == 1)
                return List.of("give", "take", "set");
            if (args.length == 2)
                return null; // offline players
            if (args.length == 3)
                return Collections.emptyList(); // amount
            if (args.length == 4)
                return currencies; // [currency]
        }
        return Collections.emptyList();
    }
}

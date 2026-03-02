package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import java.util.Collections;
import java.util.List;
import net.kyori.adventure.text.Component;
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

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        // /bal [player]
        if (label.equalsIgnoreCase("bal") || label.equalsIgnoreCase("balance") || label.equalsIgnoreCase("money")) {
            handleBalance(sender, args);
            return true;
        }

        // /pay <player> <amount>
        if (label.equalsIgnoreCase("pay")) {
            handlePay(sender, args);
            return true;
        }

        // /eco <give|take|set> <player> <amount>
        if (label.equalsIgnoreCase("eco")) {
            handleEco(sender, args);
            return true;
        }

        return false;
    }

    private void handleBalance(CommandSender sender, String[] args) {
        OfflinePlayer target;
        if (args.length > 0) {
            target = Bukkit.getOfflinePlayer(args[0]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Console must specify a player."));
            return;
        }

        double bal = plugin.getEconomyManager().getBalance(target);
        String symbol = plugin.getConfig().getString("economy.currency-symbol", "$");
        String prefix = plugin.getConfig().getString("prefix", "<gold>[AurelEconomy] <gray>");

        if (sender instanceof Player p && target.getUniqueId().equals(p.getUniqueId())) {
            String msg = plugin.getConfig().getString("economy.balance", "Balance: %amount%%symbol%");
            sender.sendMessage(mm.deserialize(prefix + msg
                    .replace("%amount%", String.format("%.2f", bal))
                    .replace("%symbol%", symbol)));
        } else {
            String msg = plugin.getConfig().getString("economy.balance-other", "Balance of %player%: %amount%%symbol%");
            sender.sendMessage(mm.deserialize(prefix + msg
                    .replace("%player%", target.getName() != null ? target.getName() : "Unknown")
                    .replace("%amount%", String.format("%.2f", bal))
                    .replace("%symbol%", symbol)));
        }
    }

    private void handlePay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can pay."));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /pay <player> <amount>"));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("You cannot pay yourself."));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Invalid amount."));
            return;
        }

        if (amount <= 0) {
            player.sendMessage(Component.text("Amount must be positive."));
            return;
        }

        if (!plugin.getEconomyManager().has(player, amount)) {
            player.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix") +
                    plugin.getConfig().getString("economy.insufficient-funds")
                            .replace("%currency%", plugin.getConfig().getString("economy.currency-name"))));
            return;
        }

        plugin.getEconomyManager().withdraw(player, amount);
        plugin.getEconomyManager().deposit(target, amount);

        String symbol = plugin.getConfig().getString("economy.currency-symbol");
        player.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix") +
                plugin.getConfig().getString("economy.paid")
                        .replace("%player%", target.getName() != null ? target.getName() : args[0])
                        .replace("%amount%", String.format("%.2f", amount))
                        .replace("%symbol%", symbol)));

        if (target.isOnline()) {
            ((Player) target).sendMessage(mm.deserialize(plugin.getConfig().getString("prefix") +
                    plugin.getConfig().getString("economy.received")
                            .replace("%player%", player.getName())
                            .replace("%amount%", String.format("%.2f", amount))
                            .replace("%symbol%", symbol)));
        }
    }

    private void handleEco(CommandSender sender, String[] args) {
        if (!sender.hasPermission("aureleconomy.admin")) {
            sender.sendMessage(Component.text("No permission."));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /eco <give|take|set> [player] <amount>"));
            return;
        }

        String action = args[0].toLowerCase();
        OfflinePlayer target;
        double amount;

        if (args.length == 2) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Console must specify a player."));
                return;
            }
            target = player;
            try {
                amount = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid amount."));
                return;
            }
        } else if (args.length >= 3) {
            target = Bukkit.getOfflinePlayer(args[1]);
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid amount."));
                return;
            }
        } else {
            sender.sendMessage(Component.text("Usage: /eco <give|take|set> [player] <amount>"));
            return;
        }

        switch (action) {
            case "give":
                plugin.getEconomyManager().deposit(target, amount);
                sender.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix") +
                        plugin.getConfig().getString("economy.admin-give")
                                .replace("%player%", target.getName() != null ? target.getName() : "You")
                                .replace("%amount%", String.valueOf(amount))));
                break;
            case "take":
                plugin.getEconomyManager().withdraw(target, amount);
                sender.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix") +
                        plugin.getConfig().getString("economy.admin-take")
                                .replace("%player%", target.getName() != null ? target.getName() : "You")
                                .replace("%amount%", String.valueOf(amount))));
                break;
            case "set":
                plugin.getEconomyManager().setBalance(target, amount);
                sender.sendMessage(mm.deserialize(plugin.getConfig().getString("prefix") +
                        plugin.getConfig().getString("economy.admin-set")
                                .replace("%player%", target.getName() != null ? target.getName() : "You")
                                .replace("%amount%", String.valueOf(amount))));
                break;
            default:
                sender.sendMessage(Component.text("Unknown action: " + action));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (label.equalsIgnoreCase("pay") && args.length == 1) {
            return null; // Return null to show online players
        }
        if (label.equalsIgnoreCase("eco")) {
            if (args.length == 1) {
                return List.of("give", "take", "set");
            }
            if (args.length == 2) {
                return null; // players
            }
        }
        return Collections.emptyList();
    }
}

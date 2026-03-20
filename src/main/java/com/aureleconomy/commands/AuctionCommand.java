package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.gui.AuctionGUI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.bukkit.command.TabExecutor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AuctionCommand implements TabExecutor {

    private final AurelEconomy plugin;

    public AuctionCommand(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players."));
            return true;
        }

        if (!player.hasPermission("aureleconomy.ah")) {
            player.sendMessage(
                    Component.text("You do not have permission to use the Auction House.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            player.openInventory(new AuctionGUI(plugin, player, false).getInventory());
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("collect")) {
            player.openInventory(new AuctionGUI(plugin, player, true).getInventory());
            return true;
        }

        if (sub.equals("offers")) {
            new com.aureleconomy.gui.OffersGUI(plugin, player).open();
            return true;
        }

        if (sub.equals("search")) {
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /ah search <query>", NamedTextColor.RED));
                return true;
            }
            StringBuilder query = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                query.append(args[i]).append(" ");
            }
            String searchStr = query.toString().trim();
            AuctionGUI gui = new AuctionGUI(plugin, player, false);
            gui.setSearchQuery(searchStr);
            gui.open();
            return true;
        }

        if (sub.equals("offer")) {
            if (args.length < 3) {
                player.sendMessage(Component.text("Usage: /ah offer <id> <amount>", NamedTextColor.RED));
                return true;
            }
            try {
                int id = Integer.parseInt(args[1]);
                BigDecimal amount = new BigDecimal(args[2]);
                AuctionItem ai = plugin.getAuctionManager().getAuctionById(id);
                if (ai == null) {
                    player.sendMessage(Component.text("Auction not found.", NamedTextColor.RED));
                    return true;
                }
                plugin.getAuctionManager().makeOffer(ai, player, amount);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Invalid ID or amount.", NamedTextColor.RED));
            }
            return true;
        }

        if (sub.equals("sell") || sub.equals("bin")) {
            if (args.length < 2) {
                player.sendMessage(Component.text("Usage: /ah " + sub + " <price>", NamedTextColor.RED));
                return true;
            }

            ItemStack item = player.getInventory().getItemInMainHand();
            if (item == null || item.getType() == Material.AIR) {
                player.sendMessage(Component.text("You must hold an item.", NamedTextColor.RED));
                return true;
            }

            if (plugin.getMarketManager().isBlacklisted(item.getType())) {
                player.sendMessage(Component.text("This item is blacklisted.", NamedTextColor.RED));
                return true;
            }

            BigDecimal price;
            try {
                price = new BigDecimal(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Invalid price.", NamedTextColor.RED));
                return true;
            }

            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                player.sendMessage(Component.text("Price must be positive.", NamedTextColor.RED));
                return true;
            }

            // Duration & Currency handling
            long durationMillis = plugin.getConfig().getLong("auction-house.default-duration", 86400) * 1000;
            String currency = plugin.getEconomyManager().getDefaultCurrency();

            if (args.length == 3) {
                // Determine if it represents a currency or a duration
                if (plugin.getConfig().getConfigurationSection("economy.currencies").contains(args[2])) {
                    currency = args[2];
                } else {
                    durationMillis = parseDuration(args[2]);
                    if (durationMillis == -1) {
                        player.sendMessage(
                                Component.text("Invalid duration (e.g., 1h, 7d, 1y). Max 1 year.", NamedTextColor.RED));
                        return true;
                    }
                }
            } else if (args.length >= 4) {
                durationMillis = parseDuration(args[2]);
                if (durationMillis == -1) {
                    player.sendMessage(
                            Component.text("Invalid duration (e.g., 1h, 7d). Max 1 year.", NamedTextColor.RED));
                    return true;
                }
                currency = args[3];
                if (!plugin.getConfig().getConfigurationSection("economy.currencies").contains(currency)) {
                    player.sendMessage(Component.text("Invalid currency: " + currency, NamedTextColor.RED));
                    return true;
                }
            }

            // Listing fee
            BigDecimal feeRate = BigDecimal.valueOf(plugin.getConfig().getDouble("auction-house.listing-fee-percent", 2.0)).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            BigDecimal days = BigDecimal.valueOf(durationMillis).divide(BigDecimal.valueOf(86400000L), 4, RoundingMode.HALF_UP);
            // Scaling formula: Base% * (1 + (Days - 1) * 0.05)
            BigDecimal scalingMultiplier = BigDecimal.ONE.add(days.subtract(BigDecimal.ONE).max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(0.05)));
            BigDecimal feeAmount = price.multiply(feeRate).multiply(scalingMultiplier).setScale(2, RoundingMode.HALF_UP);

            if (!plugin.getEconomyManager().has(player, feeAmount, currency)) {
                player.sendMessage(
                        Component.text(
                                "You cannot afford the listing fee of "
                                        + plugin.getEconomyManager().getFormattedWithSymbol(feeAmount, currency),
                                NamedTextColor.RED));
                return true;
            }

            plugin.getEconomyManager().withdraw(player, feeAmount, currency);
            boolean isBin = sub.equals("bin");

            plugin.getAuctionManager().listAuction(player.getUniqueId(), item.clone(), price, currency, isBin,
                    durationMillis,
                    feeAmount);
            player.getInventory().setItemInMainHand(null);

            player.sendMessage(
                    Component.text("Item listed for " + plugin.getEconomyManager().getFormattedWithSymbol(price, currency) + " (Fee: " + plugin.getEconomyManager().getFormattedWithSymbol(feeAmount, currency) + ")",
                            NamedTextColor.GREEN));
        }

        return true;
    }

    private long parseDuration(String input) {
        try {
            long value = Long.parseLong(input.substring(0, input.length() - 1));
            char unit = input.toLowerCase().charAt(input.length() - 1);
            long millis = switch (unit) {
                case 'h' -> value * 3600000L;
                case 'd' -> value * 86400000L;
                case 'y' -> value * 31536000000L;
                default -> -1;
            };

            // Limit to 1 year
            if (millis > 31536000000L)
                return -1;
            return millis;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subcommands = List.of("collect", "offers", "offer", "sell", "bid", "cancel", "search");
            List<String> completions = new ArrayList<>();
            for (String sub : subcommands) {
                if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
            return completions;
        }
        return Collections.emptyList();
    }
}

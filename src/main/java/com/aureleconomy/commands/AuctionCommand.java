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
                double amount = Double.parseDouble(args[2]);
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

            double price;
            try {
                price = Double.parseDouble(args[1]);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Invalid price.", NamedTextColor.RED));
                return true;
            }

            if (price <= 0) {
                player.sendMessage(Component.text("Price must be positive.", NamedTextColor.RED));
                return true;
            }

            // Duration handling
            long durationMillis = plugin.getConfig().getLong("auction-house.default-duration", 86400) * 1000;
            if (args.length >= 3) {
                durationMillis = parseDuration(args[2]);
                if (durationMillis == -1) {
                    player.sendMessage(
                            Component.text("Invalid duration (e.g., 1h, 7d, 1y). Max 1 year.", NamedTextColor.RED));
                    return true;
                }
            }

            // Listing fee
            double feeRate = plugin.getConfig().getDouble("auction-house.listing-fee-percent", 2.0) / 100.0;
            double days = durationMillis / (1000.0 * 60 * 60 * 24);
            // Scaling formula: Base% * (1 + (Days - 1) * 0.05)
            double scalingMultiplier = 1.0 + (Math.max(0, days - 1) * 0.05);
            double feeAmount = price * feeRate * scalingMultiplier;

            if (!plugin.getEconomyManager().has(player, feeAmount)) {
                player.sendMessage(
                        Component.text(
                                "You cannot afford the listing fee of " + plugin.getEconomyManager().format(feeAmount),
                                NamedTextColor.RED));
                return true;
            }

            plugin.getEconomyManager().withdraw(player, feeAmount);
            boolean isBin = sub.equals("sell");

            plugin.getAuctionManager().listAuction(player.getUniqueId(), item.clone(), price, isBin, durationMillis,
                    feeAmount);
            player.getInventory().setItemInMainHand(null);

            player.sendMessage(
                    Component.text("Item listed for " + price + " (Fee: " + String.format("%.2f", feeAmount) + ")",
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

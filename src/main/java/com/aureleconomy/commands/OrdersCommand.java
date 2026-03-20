package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.orders.BuyOrder;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class OrdersCommand implements TabExecutor {

    private final AurelEconomy plugin;

    public OrdersCommand(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        if (!player.hasPermission("aureleconomy.orders")) {
            player.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (!plugin.getConfig().getBoolean("buy-orders.enabled", true)) {
            player.sendMessage(Component.text("The Buy Orders system is currently disabled.", NamedTextColor.RED));
            return true;
        }

        // No subcommand = open the GUI
        if (args.length == 0) {
            new com.aureleconomy.gui.OrdersGUI(plugin, player, 0).open();
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "create" -> handleCreate(player, args);
            case "fill" -> handleFill(player, args);
            case "cancel" -> handleCancel(player, args);
            case "my" -> handleMy(player);
            case "search" -> handleSearch(player, args);
            default -> sendHelp(player);
        }

        return true;
    }

    // /orders create <item> <amount> <pricePerPiece>
    private void handleCreate(Player player, String[] args) {
        if (args.length < 4) {
            player.sendMessage(
                    Component.text("Usage: /orders create <item> <amount> <pricePerPiece>", NamedTextColor.RED));
            return;
        }

        Material material;
        try {
            material = Material.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text(
                    "Invalid item: " + args[1] + ". Use Minecraft material names (e.g., DIAMOND, IRON_INGOT).",
                    NamedTextColor.RED));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount <= 0)
                throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Amount must be a positive whole number.", NamedTextColor.RED));
            return;
        }

        BigDecimal pricePerPiece;
        try {
            pricePerPiece = new BigDecimal(args[3]);
            if (pricePerPiece.compareTo(BigDecimal.ZERO) <= 0)
                throw new NumberFormatException();
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Price must be a positive number.", NamedTextColor.RED));
            return;
        }

        String currency = plugin.getEconomyManager().getDefaultCurrency();
        if (args.length >= 5) {
            currency = args[4];
            if (!plugin.getConfig().getConfigurationSection("economy.currencies").contains(currency)) {
                player.sendMessage(Component.text("Invalid currency: " + currency, NamedTextColor.RED));
                return;
            }
        }

        plugin.getOrderManager().createOrder(player, material, amount, pricePerPiece, currency);
    }

    // /orders fill <orderId> [amount]
    private void handleFill(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /orders fill <orderId> [amount]", NamedTextColor.RED));
            return;
        }

        int orderId;
        try {
            orderId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Order ID must be a number. Find IDs via /orders or /orders my.",
                    NamedTextColor.RED));
            return;
        }

        // Default: fill as much as possible
        int amount = Integer.MAX_VALUE;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount <= 0)
                    throw new NumberFormatException();
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("Amount must be a positive whole number.", NamedTextColor.RED));
                return;
            }
        }

        plugin.getOrderManager().fillOrder(player, orderId, amount);
    }

    // /orders cancel <orderId>
    private void handleCancel(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /orders cancel <orderId>", NamedTextColor.RED));
            return;
        }

        int orderId;
        try {
            orderId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Order ID must be a number.", NamedTextColor.RED));
            return;
        }

        plugin.getOrderManager().cancelOrder(player, orderId);
    }

    // /orders my
    private void handleMy(Player player) {
        List<BuyOrder> myOrders = plugin.getOrderManager().getOrdersByPlayer(player.getUniqueId());
        if (myOrders.isEmpty()) {
            player.sendMessage(Component.text("You have no active buy orders.", NamedTextColor.GRAY));
            return;
        }

        player.sendMessage(
                Component.text("━━━━━━ Your Active Buy Orders ━━━━━━", NamedTextColor.GOLD, TextDecoration.BOLD));
        for (BuyOrder order : myOrders) {
            String itemName = order.getMaterial().name().replace("_", " ");
            player.sendMessage(
                    Component.text(" ID #" + order.getId(), NamedTextColor.YELLOW)
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(itemName, NamedTextColor.AQUA))
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(order.getAmountFilled() + "/" + order.getAmountRequested(),
                                    NamedTextColor.GREEN))
                            .append(Component.text(" @ ", NamedTextColor.GRAY))
                            .append(Component.text(
                                    plugin.getEconomyManager().getFormattedWithSymbol(order.getPricePerPiece(), order.getCurrency())
                                            + " each",
                                    NamedTextColor.WHITE)));
        }
        player.sendMessage(Component.text("Use /orders cancel <id> to cancel an order.", NamedTextColor.GRAY,
                TextDecoration.ITALIC));
    }

    // /orders search <query>
    private void handleSearch(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /orders search <item name>", NamedTextColor.RED));
            return;
        }

        String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).toLowerCase();
        List<BuyOrder> allOrders = plugin.getOrderManager().getActiveOrders();
        List<BuyOrder> matches = allOrders.stream()
                .filter(o -> o.getMaterial().name().toLowerCase().replace("_", " ").contains(query)
                        || o.getMaterial().name().toLowerCase().contains(query))
                .toList();

        if (matches.isEmpty()) {
            player.sendMessage(Component.text("No active orders found matching '" + query + "'.", NamedTextColor.GRAY));
            return;
        }

        player.sendMessage(Component.text("━━━━━━ Orders matching '" + query + "' ━━━━━━", NamedTextColor.GOLD,
                TextDecoration.BOLD));
        for (BuyOrder order : matches) {
            String itemName = order.getMaterial().name().replace("_", " ");
            player.sendMessage(
                    Component.text(" ID #" + order.getId(), NamedTextColor.YELLOW)
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(itemName, NamedTextColor.AQUA))
                            .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                            .append(Component.text(order.getAmountRemaining() + " needed", NamedTextColor.GREEN))
                            .append(Component.text(" @ ", NamedTextColor.GRAY))
                            .append(Component.text(
                                    plugin.getEconomyManager().getFormattedWithSymbol(order.getPricePerPiece(), order.getCurrency())
                                            + " each",
                                    NamedTextColor.WHITE)));
        }
        player.sendMessage(Component.text("Use /orders fill <id> to sell items to an order.", NamedTextColor.GRAY,
                TextDecoration.ITALIC));
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("━━━━━━ Buy Orders Help ━━━━━━", NamedTextColor.GOLD, TextDecoration.BOLD));
        player.sendMessage(Component.text("/orders", NamedTextColor.YELLOW)
                .append(Component.text(" - Open the Buy Orders GUI", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/orders create <item> <amount> <price>", NamedTextColor.YELLOW)
                .append(Component.text(" - Place a new buy order", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/orders fill <id> [amount]", NamedTextColor.YELLOW)
                .append(Component.text(" - Fulfill someone's order", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/orders cancel <id>", NamedTextColor.YELLOW)
                .append(Component.text(" - Cancel your order", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/orders my", NamedTextColor.YELLOW)
                .append(Component.text(" - List your active orders", NamedTextColor.GRAY)));
        player.sendMessage(Component.text("/orders search <query>", NamedTextColor.YELLOW)
                .append(Component.text(" - Search orders by item name", NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String sub : new String[] { "create", "fill", "cancel", "my", "search", "help" }) {
                if (sub.startsWith(partial)) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("create")) {
            String partial = args[1].toUpperCase();
            for (Material mat : Material.values()) {
                if (mat.isItem() && com.aureleconomy.market.MarketItems.isObtainable(mat)
                        && mat.name().startsWith(partial)) {
                    completions.add(mat.name());
                }
            }
        }

        return completions;
    }
}

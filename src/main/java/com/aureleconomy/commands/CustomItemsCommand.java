package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import com.aureleconomy.scanner.DiscoveryMethod;
import com.aureleconomy.scanner.UnifiedItemScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Admin command for managing discovered custom items.
 * /customitems scan - Trigger an immediate full rescan
 * /customitems list - List all discovered custom items
 * /customitems info <id> - Show details about a custom item
 * /customitems reload - Reload custom items from database + rescan
 * /customitems toggle <id> - Enable/disable a custom item in the market
 * /customitems price <id> <buy> <sell> - Set custom pricing
 */
public class CustomItemsCommand implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final AurelEconomy plugin;

    public CustomItemsCommand(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                              @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("aureleconomy.admin")) {
            sender.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (registry == null) {
            sender.sendMessage(Component.text("Custom item system is not initialized.", NamedTextColor.RED));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "scan" -> handleScan(sender, registry);
            case "list" -> handleList(sender, registry, args);
            case "info" -> handleInfo(sender, registry, args);
            case "reload" -> handleReload(sender, registry);
            case "toggle" -> handleToggle(sender, registry, args);
            case "price" -> handlePrice(sender, registry, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleScan(CommandSender sender, CustomItemRegistry registry) {
        UnifiedItemScanner scanner = plugin.getUnifiedScanner();
        if (scanner == null) {
            sender.sendMessage(Component.text("Scanner not initialized.", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <gray>Starting full rescan...</gray>"));

        // Run scan on next tick to ensure we're on main thread where needed
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            int before = registry.getTotalItems();
            scanner.scanAllPluginAPIs();
            scanner.scanPlayerInventories();
            int after = registry.getTotalItems();

            sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <green>Scan complete!</green> <gray>" +
                    after + " unique items, " + registry.getDuplicatesPrevented() + " duplicates prevented" +
                    (after > before ? " (" + (after - before) + " new)" : "") + "</gray>"));

            // Persist to database
            registry.saveToDatabase(plugin.getDatabaseManager());
        }, 1L);
    }

    private void handleList(CommandSender sender, CustomItemRegistry registry, String[] args) {
        if (registry.isEmpty()) {
            sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <gray>No custom items discovered yet.</gray>"));
            return;
        }

        int page = 1;
        if (args.length > 1) {
            try { page = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
        }

        int perPage = 10;
        List<CustomMarketItem> allItems = new ArrayList<>(registry.getAllItems());
        int totalPages = Math.max(1, (int) Math.ceil((double) allItems.size() / perPage));
        page = Math.min(page, totalPages);

        sender.sendMessage(MM.deserialize("<gold><bold>Custom Items</bold></gold> <gray>(Page " + page + "/" + totalPages +
                " - " + allItems.size() + " items)</gray>"));

        int start = (page - 1) * perPage;
        int end = Math.min(start + perPage, allItems.size());

        for (int i = start; i < end; i++) {
            CustomMarketItem item = allItems.get(i);
            String enabled = item.isEnabled() ? "<green>ON</green>" : "<red>OFF</red>";
            String price = item.getBuyPrice().compareTo(BigDecimal.ZERO) >= 0
                    ? "<gold>" + item.getBuyPrice() + "</gold>/<yellow>" + item.getSellPrice() + "</yellow>"
                    : "<gray>unset</gray>";

            sender.sendMessage(MM.deserialize(" <dark_gray>-</dark_gray> <white>" + item.getCanonicalId() +
                    "</white> <gray>from</gray> <aqua>" + item.getSourcePlugin() + "</aqua> " + enabled +
                    " <gray>price:</gray> " + price));
        }

        if (page < totalPages) {
            sender.sendMessage(MM.deserialize("<gray>Use /customitems list " + (page + 1) + " for next page.</gray>"));
        }
    }

    private void handleInfo(CommandSender sender, CustomItemRegistry registry, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /customitems info <id>", NamedTextColor.YELLOW));
            return;
        }

        String id = args[1];
        CustomMarketItem item = registry.getById(id);
        if (item == null) {
            sender.sendMessage(MM.deserialize("<red>No custom item found with ID: " + id + "</red>"));
            return;
        }

        Set<DiscoveryMethod> methods = registry.getDiscoveryMethods(id);

        sender.sendMessage(MM.deserialize("<gold><bold>=== Custom Item Info ===</bold></gold>"));
        sender.sendMessage(MM.deserialize("<gray>  ID:</gray> <white>" + item.getCanonicalId() + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Source:</gray> <aqua>" + item.getSourcePlugin() + "</aqua>"));
        sender.sendMessage(MM.deserialize("<gray>  Display Name:</gray> <white>" + item.getDisplayName() + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Material:</gray> <white>" + item.getItemStack().getType().name() + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Category:</gray> <white>" + item.getCategory() + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Enabled:</gray> " + (item.isEnabled() ? "<green>Yes</green>" : "<red>No</red>")));
        sender.sendMessage(MM.deserialize("<gray>  Buy Price:</gray> <gold>" + item.getBuyPrice() + "</gold>"));
        sender.sendMessage(MM.deserialize("<gray>  Sell Price:</gray> <yellow>" + item.getSellPrice() + "</yellow>"));
        sender.sendMessage(MM.deserialize("<gray>  PDC Key:</gray> <white>" + (item.getPdcKey() != null ? item.getPdcKey() : "N/A") + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Model Data Key:</gray> <white>" + (item.getModelDataKey() != null ? item.getModelDataKey() : "N/A") + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Lore Hash:</gray> <white>" + (item.getLoreHash() != null ? item.getLoreHash() : "N/A") + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Plugin Native ID:</gray> <white>" + (item.getPluginNativeId() != null ? item.getPluginNativeId() : "N/A") + "</white>"));
        sender.sendMessage(MM.deserialize("<gray>  Discovery Methods:</gray> <aqua>" +
                methods.stream().map(DiscoveryMethod::getDisplayName).reduce((a, b) -> a + ", " + b).orElse("None") + "</aqua>"));
    }

    private void handleReload(CommandSender sender, CustomItemRegistry registry) {
        sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <gray>Reloading from database...</gray>"));

        // Clear registry and reload
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            registry.loadFromDatabase(plugin.getDatabaseManager());

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                UnifiedItemScanner scanner = plugin.getUnifiedScanner();
                if (scanner != null) {
                    scanner.scanAllPluginAPIs();
                    scanner.scanPlayerInventories();
                }
                sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <green>Reload complete! " +
                        registry.getTotalItems() + " items loaded.</green>"));
            }, 20L);
        });
    }

    private void handleToggle(CommandSender sender, CustomItemRegistry registry, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /customitems toggle <id>", NamedTextColor.YELLOW));
            return;
        }

        String id = args[1];
        CustomMarketItem item = registry.getById(id);
        if (item == null) {
            sender.sendMessage(MM.deserialize("<red>No custom item found with ID: " + id + "</red>"));
            return;
        }

        boolean newState = !item.isEnabled();
        // Rebuild item with toggled enabled state
        CustomMarketItem updated = new CustomMarketItem.Builder()
                .canonicalId(item.getCanonicalId())
                .itemStack(item.getItemStack())
                .sourcePlugin(item.getSourcePlugin())
                .displayName(item.getDisplayName())
                .pdcKey(item.getPdcKey())
                .modelDataKey(item.getModelDataKey())
                .loreHash(item.getLoreHash())
                .pluginNativeId(item.getPluginNativeId())
                .category(item.getCategory())
                .buyPrice(item.getBuyPrice())
                .sellPrice(item.getSellPrice())
                .enabled(newState)
                .build();

        // Replace in registry
        registry.register(updated, registry.getDiscoveryMethods(id).iterator().next());

        String stateStr = newState ? "<green>enabled</green>" : "<red>disabled</red>";
        sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <white>" + id + "</white> is now " + stateStr));

        registry.saveToDatabase(plugin.getDatabaseManager());
    }

    private void handlePrice(CommandSender sender, CustomItemRegistry registry, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /customitems price <id> <buy> <sell>", NamedTextColor.YELLOW));
            return;
        }

        String id = args[1];
        CustomMarketItem item = registry.getById(id);
        if (item == null) {
            sender.sendMessage(MM.deserialize("<red>No custom item found with ID: " + id + "</red>"));
            return;
        }

        try {
            double buy = Double.parseDouble(args[2]);
            double sell = Double.parseDouble(args[3]);

            // Rebuild item with new prices
            CustomMarketItem updated = new CustomMarketItem.Builder()
                    .canonicalId(item.getCanonicalId())
                    .itemStack(item.getItemStack())
                    .sourcePlugin(item.getSourcePlugin())
                    .displayName(item.getDisplayName())
                    .pdcKey(item.getPdcKey())
                    .modelDataKey(item.getModelDataKey())
                    .loreHash(item.getLoreHash())
                    .pluginNativeId(item.getPluginNativeId())
                    .category(item.getCategory())
                    .buyPrice(BigDecimal.valueOf(buy))
                    .sellPrice(BigDecimal.valueOf(sell))
                    .enabled(item.isEnabled())
                    .build();

            Set<DiscoveryMethod> methods = registry.getDiscoveryMethods(id);
            registry.register(updated, methods.isEmpty() ? DiscoveryMethod.PDC_SCAN : methods.iterator().next());
            registry.updateCustomItemPrice(plugin.getDatabaseManager(), id, buy, sell);

            sender.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <white>" + id + "</white> price set: " +
                    "<gold>Buy: " + buy + "</gold> <yellow>Sell: " + sell + "</yellow>"));
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("Invalid price format. Use numbers.", NamedTextColor.RED));
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(MM.deserialize("<gold><bold>Custom Items Commands</bold></gold>"));
        sender.sendMessage(MM.deserialize("<yellow>/customitems scan</yellow> <gray>- Trigger an immediate rescan</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/customitems list</yellow> <gray>- List all discovered items</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/customitems info <id></yellow> <gray>- Show item details</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/customitems reload</yellow> <gray>- Reload from database + rescan</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/customitems toggle <id></yellow> <gray>- Enable/disable item</gray>"));
        sender.sendMessage(MM.deserialize("<yellow>/customitems price <id> <buy> <sell></yellow> <gray>- Set pricing</gray>"));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("aureleconomy.admin")) return List.of();

        if (args.length == 1) {
            return List.of("scan", "list", "info", "reload", "toggle", "price");
        }

        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (registry == null) return List.of();

        if (args.length == 2 && (args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("toggle")
                || args[0].equalsIgnoreCase("price"))) {
            return new ArrayList<>(registry.getAllItems().stream()
                    .map(CustomMarketItem::getCanonicalId)
                    .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                    .toList());
        }

        return List.of();
    }
}

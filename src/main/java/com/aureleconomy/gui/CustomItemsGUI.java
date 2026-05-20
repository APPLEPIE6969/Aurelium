package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import com.aureleconomy.scanner.DiscoveryMethod;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.*;

/**
 * Admin GUI for browsing and managing discovered custom items.
 * Paginated display with click-to-view details, toggle, and price editing.
 */
public class CustomItemsGUI extends GUIHolder {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int ITEMS_PER_PAGE = 45;

    private final AurelEconomy plugin;
    private final int page;
    private final Map<Integer, CustomMarketItem> itemSlots = new HashMap<>();
    private CustomMarketItem selectedItem = null;

    public CustomItemsGUI(AurelEconomy plugin, int page) {
        this.plugin = plugin;
        this.page = page;
        this.inventory = plugin.getServer().createInventory(this, 54,
                MM.deserialize("<gradient:gold:yellow><bold>Custom Items</bold></gradient>"));
        setupItems();
    }

    private void setupItems() {
        itemSlots.clear();
        inventory.clear();

        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (registry == null) return;

        List<CustomMarketItem> allItems = new ArrayList<>(registry.getAllItems());
        int totalPages = Math.max(1, (int) Math.ceil((double) allItems.size() / ITEMS_PER_PAGE));

        // Navigation bar
        inventory.setItem(45, new ItemBuilder(Material.BARRIER)
                .name(MM.deserialize("<red><bold>Back</bold></red>").decoration(TextDecoration.ITALIC, false))
                .build());

        inventory.setItem(49, new ItemBuilder(Material.BOOK)
                .name(MM.deserialize("<white>Page <gold>" + (page + 1) + "</gold>/<gold>" + totalPages + "</gold></white>")
                        .decoration(TextDecoration.ITALIC, false))
                .lore(Component.text(allItems.size() + " custom items discovered", NamedTextColor.GRAY))
                .build());

        if (page > 0) {
            inventory.setItem(48, new ItemBuilder(Material.SPECTRAL_ARROW)
                    .name(MM.deserialize("<yellow><bold>Previous Page</bold></yellow>").decoration(TextDecoration.ITALIC, false))
                    .build());
        }
        if (page < totalPages - 1) {
            inventory.setItem(50, new ItemBuilder(Material.SPECTRAL_ARROW)
                    .name(MM.deserialize("<yellow><bold>Next Page</bold></yellow>").decoration(TextDecoration.ITALIC, false))
                    .build());
        }

        // Rescan button
        inventory.setItem(53, new ItemBuilder(Material.COMPASS)
                .name(MM.deserialize("<aqua><bold>Rescan</bold></aqua>").decoration(TextDecoration.ITALIC, false))
                .lore(Component.text("Click to trigger a full rescan", NamedTextColor.GRAY))
                .build());

        // Items
        int start = page * ITEMS_PER_PAGE;
        int end = Math.min(start + ITEMS_PER_PAGE, allItems.size());

        for (int i = start; i < end; i++) {
            CustomMarketItem item = allItems.get(i);
            int slot = i - start;

            ItemStack display = item.getItemStack().clone();
            ItemMeta meta = display.getItemMeta();
            if (meta == null) continue;

            meta.displayName(MM.deserialize("<white><bold>" + item.getDisplayName() + "</bold></white>")
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MM.deserialize("<gray>ID:</gray> <aqua>" + item.getCanonicalId() + "</aqua>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Source:</gray> <white>" + item.getSourcePlugin() + "</white>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Category:</gray> <white>" + item.getCategory() + "</white>")
                    .decoration(TextDecoration.ITALIC, false));

            if (item.getBuyPrice().compareTo(BigDecimal.ZERO) >= 0) {
                lore.add(MM.deserialize("<gray>Buy:</gray> <gold>" + item.getBuyPrice() + "</gold> <gray>Sell:</gray> <yellow>" + item.getSellPrice() + "</yellow>")
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(MM.deserialize("<gray>Price:</gray> <dark_gray>unset</dark_gray>")
                        .decoration(TextDecoration.ITALIC, false));
            }

            Set<DiscoveryMethod> methods = plugin.getCustomItemRegistry().getDiscoveryMethods(item.getCanonicalId());
            String methodStr = methods.stream().map(DiscoveryMethod::getDisplayName).reduce((a, b) -> a + ", " + b).orElse("");
            lore.add(MM.deserialize("<gray>Found by:</gray> <aqua>" + methodStr + "</aqua>")
                    .decoration(TextDecoration.ITALIC, false));

            String status = item.isEnabled() ? "<green>Enabled</green>" : "<red>Disabled</red>";
            lore.add(Component.empty());
            lore.add(MM.deserialize("<yellow>Click</yellow> <gray>for details</gray> " + status)
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(lore);
            display.setItemMeta(meta);
            inventory.setItem(slot, display);
            itemSlots.put(slot, item);
        }
    }

    private void showDetail(Player player, CustomMarketItem item) {
        selectedItem = item;
        inventory.clear();
        itemSlots.clear();

        ItemStack display = item.getItemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            meta.displayName(MM.deserialize("<white><bold>" + item.getDisplayName() + "</bold></white>")
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MM.deserialize("<gray>Canonical ID:</gray> <aqua>" + item.getCanonicalId() + "</aqua>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Source Plugin:</gray> <white>" + item.getSourcePlugin() + "</white>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Material:</gray> <white>" + item.getItemStack().getType().name() + "</white>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Category:</gray> <white>" + item.getCategory() + "</white>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>PDC Key:</gray> <white>" + (item.getPdcKey() != null ? item.getPdcKey() : "N/A") + "</white>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Model Data Key:</gray> <white>" + (item.getModelDataKey() != null ? item.getModelDataKey() : "N/A") + "</white>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Lore Hash:</gray> <white>" + (item.getLoreHash() != null ? item.getLoreHash() : "N/A") + "</white>").decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<gray>Plugin Native ID:</gray> <white>" + (item.getPluginNativeId() != null ? item.getPluginNativeId() : "N/A") + "</white>").decoration(TextDecoration.ITALIC, false));
            if (item.getBuyPrice().compareTo(BigDecimal.ZERO) >= 0) {
                lore.add(MM.deserialize("<gray>Buy Price:</gray> <gold>" + item.getBuyPrice() + "</gold>").decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<gray>Sell Price:</gray> <yellow>" + item.getSellPrice() + "</yellow>").decoration(TextDecoration.ITALIC, false));
            }
            Set<DiscoveryMethod> methods = plugin.getCustomItemRegistry().getDiscoveryMethods(item.getCanonicalId());
            String methodStr = methods.stream().map(DiscoveryMethod::getDisplayName).reduce((a, b) -> a + ", " + b).orElse("None");
            lore.add(MM.deserialize("<gray>Discovery Methods:</gray> <aqua>" + methodStr + "</aqua>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            display.setItemMeta(meta);
        }
        inventory.setItem(13, display);

        // Toggle button
        Material toggleMat = item.isEnabled() ? Material.LIME_DYE : Material.GRAY_DYE;
        String toggleText = item.isEnabled() ? "<red>Disable</red>" : "<green>Enable</green>";
        inventory.setItem(29, new ItemBuilder(toggleMat)
                .name(MM.deserialize(toggleText).decoration(TextDecoration.ITALIC, false))
                .lore(Component.text("Click to toggle in market", NamedTextColor.GRAY))
                .build());

        // Edit price button
        inventory.setItem(33, new ItemBuilder(Material.GOLD_NUGGET)
                .name(MM.deserialize("<gold><bold>Edit Price</bold></gold>").decoration(TextDecoration.ITALIC, false))
                .lore(Component.text("Click to set buy/sell price", NamedTextColor.GRAY))
                .build());

        // Back button
        inventory.setItem(45, new ItemBuilder(Material.BARRIER)
                .name(MM.deserialize("<red><bold>Back</bold></red>").decoration(TextDecoration.ITALIC, false))
                .build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        int slot = event.getSlot();

        if (selectedItem != null) {
            // Detail view
            if (slot == 45) {
                // Back to list
                selectedItem = null;
                setupItems();
            } else if (slot == 29) {
                // Toggle
                handleToggle(player, selectedItem);
                showDetail(player, selectedItem);
            } else if (slot == 33) {
                // Edit price via chat prompt
                handleEditPrice(player, selectedItem);
            }
            return;
        }

        // List view
        if (slot == 45) {
            player.closeInventory();
        } else if (slot == 48 && page > 0) {
            new CustomItemsGUI(plugin, page - 1).open(player);
        } else if (slot == 50) {
            CustomItemRegistry registry = plugin.getCustomItemRegistry();
            if (registry != null) {
                int totalPages = Math.max(1, (int) Math.ceil((double) registry.getTotalItems() / ITEMS_PER_PAGE));
                if (page < totalPages - 1) {
                    new CustomItemsGUI(plugin, page + 1).open(player);
                }
            }
        } else if (slot == 53) {
            // Rescan
            player.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <gray>Rescanning...</gray>"));
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (plugin.getUnifiedScanner() != null) {
                    plugin.getUnifiedScanner().scanAllPluginAPIs();
                    plugin.getUnifiedScanner().scanPlayerInventories();
                }
                player.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <green>Rescan complete!</green>"));
                new CustomItemsGUI(plugin, 0).open(player);
            }, 1L);
        } else if (itemSlots.containsKey(slot)) {
            showDetail(player, itemSlots.get(slot));
        }
    }

    private void handleToggle(Player player, CustomMarketItem item) {
        boolean newState = !item.isEnabled();
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

        Set<DiscoveryMethod> methods = plugin.getCustomItemRegistry().getDiscoveryMethods(item.getCanonicalId());
        plugin.getCustomItemRegistry().register(updated, methods.isEmpty() ? DiscoveryMethod.PDC_SCAN : methods.iterator().next());
        plugin.getCustomItemRegistry().saveToDatabase(plugin.getDatabaseManager());

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, newState ? 1.2f : 0.8f);
        String stateStr = newState ? "<green>enabled</green>" : "<red>disabled</red>";
        player.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <white>" + item.getCanonicalId() + "</white> " + stateStr));
    }

    private void handleEditPrice(Player player, CustomMarketItem item) {
        player.closeInventory();
        player.sendMessage(MM.deserialize("<gold><bold>Set Buy Price</bold></gold> <gray>(type number or 'cancel')</gray>"));
        plugin.getChatPromptManager().prompt(player, (buyInput) -> {
            if (buyInput.equalsIgnoreCase("cancel")) {
                new CustomItemsGUI(plugin, 0).open(player);
                return;
            }
            try {
                double buy = Double.parseDouble(buyInput);
                player.sendMessage(MM.deserialize("<gold><bold>Set Sell Price</bold></gold> <gray>(type number or 'cancel')</gray>"));
                plugin.getChatPromptManager().prompt(player, (sellInput) -> {
                    if (sellInput.equalsIgnoreCase("cancel")) {
                        new CustomItemsGUI(plugin, 0).open(player);
                        return;
                    }
                    try {
                        double sell = Double.parseDouble(sellInput);
                        plugin.getCustomItemRegistry().updateCustomItemPrice(plugin.getDatabaseManager(),
                                item.getCanonicalId(), buy, sell);
                        player.sendMessage(MM.deserialize("<aqua>[CustomItems]</aqua> <green>Price set for " +
                                item.getCanonicalId() + ": Buy=" + buy + " Sell=" + sell + "</green>"));
                        new CustomItemsGUI(plugin, 0).open(player);
                    } catch (NumberFormatException e) {
                        player.sendMessage(MM.deserialize("<red>Invalid price. Reopen the GUI.</red>"));
                    }
                });
            } catch (NumberFormatException e) {
                player.sendMessage(MM.deserialize("<red>Invalid price. Reopen the GUI.</red>"));
            }
        });
    }

    public static void refreshAllViewers(org.bukkit.Server server) {
        for (Player player : server.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof CustomItemsGUI gui) {
                gui.setupItems();
            }
        }
    }
}

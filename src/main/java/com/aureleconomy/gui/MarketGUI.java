package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MarketGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Category category;
    private final int page;
    private final Map<Integer, Category> slots = new HashMap<>();
    private final Map<Integer, MarketEntry> itemSlots = new HashMap<>();
    private String searchQuery = null;
    private static final Map<java.util.UUID, Long> clickCooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 100; // 0.1s cooldown

    public MarketGUI(AurelEconomy plugin, Player player, Category category, int page) {
        this.plugin = plugin;
        this.category = category;
        this.page = page;

        this.inventory = plugin.getServer().createInventory(this, 54,
                Component.text("Market - " + (category != null ? category.name : "Categories")));

        if (category == null) {
            setupCategories();
        } else {
            setupItems();
        }
    }

    public MarketGUI(AurelEconomy plugin, Player player) {
        this(plugin, player, null, 0);
    }

    private void setupCategories() {
        int[] slotIndices = { 10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22 };
        int index = 0;

        for (Category cat : Category.values()) {
            if (index >= slotIndices.length)
                break;

            inventory.setItem(slotIndices[index], new ItemBuilder(cat.icon)
                    .name(Component.text(cat.name, NamedTextColor.GOLD))
                    .lore(Component.text("Click to view items", NamedTextColor.GRAY))
                    .build());

            // Map slot to category for click handling (though we can just use index if
            // ordered, map is safer)
            // Actually, we need to know WHICH category was clicked.
            // Let's store a map of Slot -> Category
            // But I can't easily do that without a field.
            // I'll add private final Map<Integer, Category> categorySlots = new
            // HashMap<>();
            // wait, I can just iterate values again or store it.
            // Storing is better.
            this.slots.put(slotIndices[index], cat);

            index++;
        }

        // Search Button
        inventory.setItem(46, new ItemBuilder(Material.COMPASS)
                .name(Component.text("Search Items", NamedTextColor.AQUA))
                .lore(Component.text("Click to search by name", NamedTextColor.GRAY))
                .build());
    }

    private void setupSearch() {
        itemSlots.clear();
        inventory.clear();

        List<MarketEntry> matches = new java.util.ArrayList<>();
        String query = searchQuery.toLowerCase();

        for (Category cat : Category.values()) {
            if (cat == Category.ALL_ITEMS)
                continue;
            for (MarketEntry entry : MarketItems.getItems(cat)) {
                String name = (entry.customName != null ? entry.customName : entry.material.name()).toLowerCase();
                if (name.contains(query)) {
                    matches.add(entry);
                }
            }
        }

        renderItems(matches);
    }

    private void setupItems() {
        List<MarketEntry> allItems = MarketItems.getItems(category);
        renderItems(allItems);
    }

    private void renderItems(List<MarketEntry> allItems) {
        // Filter blacklisted
        allItems = allItems.stream().filter(entry -> !plugin.getMarketManager().isBlacklisted(entry.material)).toList();

        int itemsPerPage = 45;
        int totalItems = allItems.size();
        int totalPages = (int) Math.ceil((double) totalItems / itemsPerPage);

        // Search Button
        inventory.setItem(46, new ItemBuilder(Material.COMPASS)
                .name(Component.text(searchQuery != null ? "Change Search: " + searchQuery : "Search Market",
                        NamedTextColor.AQUA))
                .lore(Component.text("Click to find specific items", NamedTextColor.GRAY))
                .build());

        // Page Indicator Book (Middle)
        inventory.setItem(49, new ItemBuilder(Material.BOOK)
                .name(Component.text("Page " + (page + 1) + " of " + totalPages, NamedTextColor.WHITE))
                .lore(Component.text(
                        searchQuery != null ? "Search Results: " + totalItems : "Total Items: " + totalItems,
                        NamedTextColor.GRAY))
                .build());

        // Navigation Arrows
        if (page > 0) {
            inventory.setItem(48,
                    new ItemBuilder(Material.PAPER).name(Component.text("Previous Page", NamedTextColor.YELLOW))
                            .build());
        }

        if (page < totalPages - 1) {
            inventory.setItem(50,
                    new ItemBuilder(Material.PAPER).name(Component.text("Next Page", NamedTextColor.YELLOW)).build());
        }

        // Back button / Clear Search (moved from 49 to 45 to make room for book)
        inventory.setItem(45, new ItemBuilder(Material.BARRIER)
                .name(Component.text(searchQuery != null ? "Clear Search" : "Back to Categories", NamedTextColor.RED))
                .build());

        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

        for (int i = startIndex; i < endIndex; i++) {
            MarketEntry entry = allItems.get(i);
            int slot = i - startIndex;

            itemSlots.put(slot, entry);

            // Price Logic
            double buyPrice;
            String currency;
            if (entry.material == Material.SPAWNER && entry.customName != null) {
                buyPrice = plugin.getMarketManager().getBuyPrice(entry.customName);
                currency = plugin.getMarketManager().getCurrency(entry.customName);
            } else {
                buyPrice = plugin.getMarketManager().getBuyPrice(entry.material);
                currency = plugin.getMarketManager().getCurrency(entry.material);
            }

            // Item Creation
            ItemStack item;
            if (entry.material == Material.SPAWNER && entry.customName != null) {
                // Create Spawner with Meta
                item = new ItemStack(Material.SPAWNER);
                BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
                CreatureSpawner spawner = (CreatureSpawner) meta.getBlockState();

                // Parse name "Zombie Spawner" -> ZOMBIE
                try {
                    String mobName = entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                    EntityType type = EntityType.valueOf(mobName);
                    spawner.setSpawnedType(type);
                    meta.setBlockState(spawner);
                } catch (IllegalArgumentException e) {
                    plugin.getComponentLogger().warn("Invalid entity type in MarketItems: " + entry.customName);
                }

                meta.displayName(Component.text(entry.customName, NamedTextColor.AQUA));
                item.setItemMeta(meta);
            } else {
                // Standard Item
                Component name = entry.customName != null ? Component.text(entry.customName, NamedTextColor.AQUA)
                        : Component.translatable(entry.material.translationKey(), NamedTextColor.AQUA);

                item = new ItemBuilder(entry.material).name(name).build();
            }

            ItemBuilder builder = new ItemBuilder(item);

            if (buyPrice > 0) {
                builder.lore(
                        Component.text("Cost: " + plugin.getEconomyManager().format(buyPrice, currency),
                                NamedTextColor.GREEN));
                builder.lore(Component.text("Left-Click to Buy (1)", NamedTextColor.YELLOW));
                builder.lore(Component.text("Shift-Left-Click to Buy (64)", NamedTextColor.YELLOW));
            }

            // Sell logic removed (User Request)

            inventory.setItem(slot, builder.build());
        }
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();

        // Prevent clicking in bottom inventory entirely
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(player.getInventory())) {
            return;
        }

        int slot = event.getSlot();

        if (category == null) {
            // Main Menu
            if (slots.containsKey(slot)) {
                new MarketGUI(plugin, player, slots.get(slot), 0).open(player);
            } else if (slot == 46) {
                promptSearch(player);
            }
        } else {
            // Item Menu
            if (slot == 45 || (slot == 49 && searchQuery != null)) { // Back or middle book if searching
                if (searchQuery != null) {
                    searchQuery = null;
                    refresh();
                } else {
                    new MarketGUI(plugin, player).open(player);
                }
            } else if (slot == 48 && page > 0) {
                new MarketGUI(plugin, player, category, page - 1).open(player);
            } else if (slot == 50) {
                // Calculate total pages for limit check
                List<MarketEntry> allItems;
                if (searchQuery != null) {
                    allItems = new java.util.ArrayList<>();
                    String query = searchQuery.toLowerCase();
                    for (Category cat : Category.values()) {
                        if (cat == Category.ALL_ITEMS)
                            continue;
                        for (MarketEntry entry : MarketItems.getItems(cat)) {
                            String name = (entry.customName != null ? entry.customName : entry.material.name())
                                    .toLowerCase();
                            if (name.contains(query))
                                allItems.add(entry);
                        }
                    }
                } else {
                    allItems = MarketItems.getItems(category);
                }

                // Filter blacklisted
                allItems = allItems.stream().filter(entry -> !plugin.getMarketManager().isBlacklisted(entry.material))
                        .collect(java.util.stream.Collectors.toList());
                int itemsPerPage = 45;
                int totalPages = (int) Math.ceil((double) allItems.size() / itemsPerPage);

                if (page < totalPages - 1) {
                    new MarketGUI(plugin, player, category, page + 1).open(player);
                }
            } else if (slot == 46) {
                promptSearch(player);
            } else if (itemSlots.containsKey(slot)) {

                long now = System.currentTimeMillis();
                if (clickCooldowns.containsKey(player.getUniqueId())) {
                    long lastClick = clickCooldowns.get(player.getUniqueId());
                    if (now - lastClick < COOLDOWN_MS) {
                        return; // Ignore click
                    }
                }
                clickCooldowns.put(player.getUniqueId(), now);

                handleTransaction(player, itemSlots.get(slot), event.isLeftClick(), event.isShiftClick());
            }
        }
    }

    private void handleTransaction(Player player, MarketEntry entry, boolean isBuy, boolean isShift) {
        int amount = isShift ? 64 : 1;

        // Price Calculation
        double unitPrice;
        String currency;
        if (entry.material == Material.SPAWNER && entry.customName != null) {
            unitPrice = plugin.getMarketManager().getBuyPrice(entry.customName);
            currency = plugin.getMarketManager().getCurrency(entry.customName);
        } else {
            unitPrice = plugin.getMarketManager().getBuyPrice(entry.material);
            currency = plugin.getMarketManager().getCurrency(entry.material);
        }

        // Sell Logic uses SELL price now
        if (!isBuy) {
            if (entry.material == Material.SPAWNER && entry.customName != null) {
                unitPrice = plugin.getMarketManager().getSellPrice(entry.customName);
            } else {
                unitPrice = plugin.getMarketManager().getSellPrice(entry.material);
            }
        }

        if (unitPrice <= 0) {
            player.sendMessage(Component.text("This action is disabled for this item.", NamedTextColor.RED));
            return;
        }

        double totalPrice = unitPrice * amount;

        if (isBuy) {
            if (plugin.getEconomyManager().getBalance(player, currency) >= totalPrice) {
                // Give Item

                // Wait, finding by material might find WRONG spawner if multiple types exist
                // Better: Re-create the item or clone from the slot
                // We know the slot!
                // But we are in handleTransaction, check slot?
                // We have 'entry'.
                // Let's re-create logic or grab from inventory if we know the slot.
                // We can't know the slot here easily unless passed.
                // Let's just re-use the creation logic or simplify.

                // RE-CREATION (Safe)
                ItemStack item;
                if (entry.material == Material.SPAWNER && entry.customName != null) {
                    item = new ItemStack(Material.SPAWNER);
                    BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
                    CreatureSpawner spawner = (CreatureSpawner) meta.getBlockState();
                    String mobName = entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                    try {
                        spawner.setSpawnedType(EntityType.valueOf(mobName));
                    } catch (Exception ignored) {
                    }
                    meta.setBlockState(spawner);
                    meta.displayName(Component.text(entry.customName, NamedTextColor.AQUA));
                    item.setItemMeta(meta);
                } else if (entry.material == Material.ENCHANTED_BOOK && entry.customName != null) {
                    item = com.aureleconomy.market.MarketItems.createEnchantedBook(entry.customName);
                } else {
                    item = new ItemStack(entry.material);
                }
                item.setAmount(amount);

                if (com.aureleconomy.utils.InventoryUtils.hasSpace(player.getInventory(), item, amount)) {
                    plugin.getEconomyManager().withdraw(player, totalPrice, currency);
                    player.getInventory().addItem(item);

                    // Dynamic Pricing Trigger
                    String key = (entry.material == Material.SPAWNER && entry.customName != null) ? entry.customName
                            : entry.material.name();
                    plugin.getMarketManager().onTransaction(key, true, amount);

                    player.sendMessage(Component.text(
                            "Bought " + amount + "x "
                                    + (entry.customName != null ? entry.customName : entry.material.name()),
                            NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Not enough space in inventory.", NamedTextColor.RED));
                }
            } else {
                player.sendMessage(Component.text("Insufficient funds!", NamedTextColor.RED));
            }
        } else {
            // Sell Logic Removed
            player.sendMessage(Component.text("Selling via Market is disabled. Use /sell.", NamedTextColor.RED));
        }
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    private void promptSearch(Player player) {
        player.closeInventory();
        player.sendMessage(
                Component.text("Type your search query in chat (or type 'cancel' to abort):", NamedTextColor.AQUA));
        plugin.getChatPromptManager().prompt(player, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                open(player);
                return;
            }
            this.searchQuery = input;
            refresh();
            open(player);
        });
    }

    public void refresh() {
        inventory.clear();
        if (searchQuery != null) {
            setupSearch();
        } else if (category == null) {
            setupCategories();
        } else {
            setupItems();
        }
    }
}

package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.math.BigDecimal;
import java.util.List;

public class OrderMaterialGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final Inventory inventory;
    private final String category;
    private int page;
    private final String searchQuery;
    private final List<MarketEntry> categoryItems;

    public OrderMaterialGUI(AurelEconomy plugin, Player player, String category, int page) {
        this(plugin, player, category, page, "");
    }

    public OrderMaterialGUI(AurelEconomy plugin, Player player, String category, int page, String searchQuery) {
        this.plugin = plugin;
        this.player = player;
        this.category = category;
        this.page = page;
        this.searchQuery = searchQuery == null ? "" : searchQuery.toLowerCase();

        String title = "Select Material: " + category;
        if (!this.searchQuery.isEmpty()) {
            title = "Search (" + category + "): " + this.searchQuery;
        }

        this.inventory = Bukkit.createInventory(this, 54,
                Component.text(title, NamedTextColor.DARK_GRAY));

        List<MarketEntry> rawItems = plugin.getMarketManager().getOrderItemsByCategory(category);
        if (this.searchQuery.isEmpty()) {
            this.categoryItems = rawItems;
        } else {
            this.categoryItems = rawItems.stream().filter(entry -> {
                String name = entry.customName != null ? entry.customName : entry.material.name().replace("_", " ");
                return name.toLowerCase().contains(this.searchQuery);
            }).toList();
        }

        plugin.addViewer(player);
    }

    public void open() {
        setupItems();
        player.openInventory(inventory);
    }

    public void refresh() {
        setupItems();
    }

    private void setupItems() {
        inventory.clear();

        int startIdx = page * 45;
        for (int i = 0; i < 45; i++) {
            if (startIdx + i >= categoryItems.size())
                break;

            MarketEntry entry = categoryItems.get(startIdx + i);

            // --- Item Rendering ---
            ItemStack item;
            if (entry.material == Material.SPAWNER && entry.customName != null) {
                // Spawner rendering
                item = new ItemStack(Material.SPAWNER);
                org.bukkit.inventory.meta.BlockStateMeta meta = (org.bukkit.inventory.meta.BlockStateMeta) item
                        .getItemMeta();
                org.bukkit.block.CreatureSpawner spawner = (org.bukkit.block.CreatureSpawner) meta.getBlockState();
                try {
                    String mobName = entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                    org.bukkit.entity.EntityType type = org.bukkit.entity.EntityType.valueOf(mobName);
                    spawner.setSpawnedType(type);
                    meta.setBlockState(spawner);
                } catch (IllegalArgumentException e) {
                    plugin.getComponentLogger().warn("Invalid entity type in MarketItems: " + entry.customName);
                }
                meta.displayName(Component.text(entry.customName, NamedTextColor.AQUA));
                item.setItemMeta(meta);
            } else if (entry.material == Material.ENCHANTED_BOOK && entry.customName != null) {
                // Enchanted Book rendering
                item = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) item.getItemMeta();
                meta.displayName(Component.text(entry.customName, NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD));

                // Try to apply the actual enchantment glow
                EnchantmentData enchData = parseEnchantment(entry.customName);
                if (enchData != null) {
                    meta.addStoredEnchant(enchData.enchantment, enchData.level, true);
                }
                item.setItemMeta(meta);
            } else {
                // Standard Item
                Component itemNameComponent = entry.customName != null
                        ? Component.text(entry.customName, NamedTextColor.AQUA)
                        : Component.translatable(entry.material.translationKey(), NamedTextColor.AQUA);
                item = new ItemBuilder(entry.material).name(itemNameComponent).build();
            }

            // --- Price Display ---
            ItemBuilder builder = new ItemBuilder(item);

            String priceKey = (entry.customName != null) ? entry.customName : entry.material.name();
            BigDecimal marketBuyPrice = plugin.getMarketManager().getBuyPrice(priceKey);

            if (marketBuyPrice.compareTo(BigDecimal.ZERO) > 0 && entry.price.compareTo(BigDecimal.ONE) != 0) {
                builder.lore(
                        Component.text("Market Value: ", NamedTextColor.GRAY)
                                .append(Component.text(plugin.getEconomyManager().format(marketBuyPrice),
                                        NamedTextColor.GREEN)));
            } else {
                // Check last sold price (For ALL_ITEMS category default 1.0 items)
                BigDecimal lastSold = plugin.getOrderManager().getLastSoldPrice(priceKey);
                if (lastSold != null) {
                    builder.lore(
                            Component.text("Last Sold For: ", NamedTextColor.GRAY)
                                    .append(Component.text(plugin.getEconomyManager().format(lastSold),
                                            NamedTextColor.GOLD)));
                } else {
                    builder.lore(Component.text("Unvalued (No Market/Trade Data)", NamedTextColor.DARK_GRAY));
                }
            }

            builder.lore(
                    Component.empty(),
                    Component.text("Click to request this item!", NamedTextColor.GREEN, TextDecoration.ITALIC));

            inventory.setItem(i, builder.build());
        }

        // Bottom Row navigation
        inventory.setItem(45, new ItemBuilder(Material.BARRIER)
                .name(Component.text("Back to Categories", NamedTextColor.RED)).build());

        if (page > 0) {
            inventory.setItem(48, new ItemBuilder(Material.ARROW)
                    .name(Component.text("Previous Page", NamedTextColor.YELLOW)).build());
        }

        if (startIdx + 45 < categoryItems.size()) {
            inventory.setItem(50,
                    new ItemBuilder(Material.ARROW).name(Component.text("Next Page", NamedTextColor.YELLOW)).build());
        }

        // Search Button
        inventory.setItem(49, new ItemBuilder(Material.OAK_SIGN)
                .name(Component.text("Search Items", NamedTextColor.AQUA, TextDecoration.BOLD))
                .lore(Component.text("Click to filter this list", NamedTextColor.GRAY)).build());
    }

    public String getCategory() {
        return category;
    }

    public int getPage() {
        return page;
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null)
            return;

        int slot = event.getRawSlot();
        if (slot == 45) { // Back to Categories
            new OrderCategoryGUI(plugin, player).open();
        } else if (slot == 48 && page > 0) {
            page--;
            setupItems();
        } else if (slot == 49) {
            player.closeInventory();
            player.sendMessage(Component.text("Enter search query in chat, or type 'cancel':", NamedTextColor.YELLOW));
            plugin.getChatPromptManager().prompt(player, query -> {
                if (query.equalsIgnoreCase("cancel")) {
                    player.sendMessage(Component.text("Search cancelled.", NamedTextColor.RED));
                    new OrderMaterialGUI(plugin, player, category, 0, searchQuery).open(); // reopen
                    return;
                }
                new OrderMaterialGUI(plugin, player, category, 0, query).open();
            });
        } else if (slot == 50 && (page * 45) + 45 < categoryItems.size()) {
            page++;
            setupItems();
        } else if (slot < 45) {
            int entryIndex = (page * 45) + slot;
            if (entryIndex < categoryItems.size()) {
                com.aureleconomy.market.MarketItems.MarketEntry entry = categoryItems.get(entryIndex);
                player.closeInventory();

                String itemName = entry.customName != null ? entry.customName
                        : entry.material.name().replace("_", " ").toLowerCase();
                player.sendMessage(
                        Component.text("How many " + itemName + " do you want to buy?", NamedTextColor.YELLOW));
                player.sendMessage(
                        Component.text("Type a number in chat, or type 'cancel' to abort.", NamedTextColor.GRAY));

                plugin.getChatPromptManager().prompt(player, amountStr -> {
                    if (amountStr.equalsIgnoreCase("cancel")) {
                        player.sendMessage(Component.text("Order creation cancelled.", NamedTextColor.RED));
                        return;
                    }
                    try {
                        int amount = Integer.parseInt(amountStr);
                        if (amount <= 0)
                            throw new NumberFormatException();

                        player.sendMessage(Component.text("You want to buy " + amount + " " + itemName
                                + ". What is your MAXIMUM price per piece?", NamedTextColor.YELLOW));
                        player.sendMessage(Component.text(
                                "Type a number (e.g., 5.5) in chat, or type 'cancel' to abort.", NamedTextColor.GRAY));

                        plugin.getChatPromptManager().prompt(player, priceStr -> {
                            if (priceStr.equalsIgnoreCase("cancel")) {
                                player.sendMessage(Component.text("Order creation cancelled.", NamedTextColor.RED));
                                return;
                            }
                            try {
                                BigDecimal pricePerPiece = new BigDecimal(priceStr);
                                if (pricePerPiece.compareTo(BigDecimal.ZERO) <= 0)
                                    throw new NumberFormatException();

                                // Create the order
                                plugin.getOrderManager().createOrder(player, entry.material, amount, pricePerPiece,
                                        plugin.getEconomyManager().getDefaultCurrency());

                            } catch (NumberFormatException ex) {
                                player.sendMessage(
                                        Component.text("Invalid price. Order creation cancelled.", NamedTextColor.RED));
                            }
                        });

                    } catch (NumberFormatException ex) {
                        player.sendMessage(
                                Component.text("Invalid quantity. Order creation cancelled.", NamedTextColor.RED));
                    }
                });
            }
        }
    }

    // --- Enchantment Parsing Utility ---
    private static class EnchantmentData {
        Enchantment enchantment;
        int level;

        EnchantmentData(Enchantment enchantment, int level) {
            this.enchantment = enchantment;
            this.level = level;
        }
    }

    private static EnchantmentData parseEnchantment(String name) {
        // Parse level from end (e.g. "Sharpness V" -> level 5)
        int level = 1;
        String enchName = name;

        // Check for roman numeral suffix
        if (name.endsWith(" V")) {
            level = 5;
            enchName = name.substring(0, name.length() - 2);
        } else if (name.endsWith(" IV")) {
            level = 4;
            enchName = name.substring(0, name.length() - 3);
        } else if (name.endsWith(" III")) {
            level = 3;
            enchName = name.substring(0, name.length() - 4);
        } else if (name.endsWith(" II")) {
            level = 2;
            enchName = name.substring(0, name.length() - 3);
        } else if (name.endsWith(" I")) {
            level = 1;
            enchName = name.substring(0, name.length() - 2);
        }

        // Map name to Enchantment
        Enchantment ench = switch (enchName.toLowerCase().trim()) {
            case "protection" -> Enchantment.PROTECTION;
            case "fire protection" -> Enchantment.FIRE_PROTECTION;
            case "blast protection" -> Enchantment.BLAST_PROTECTION;
            case "projectile protection" -> Enchantment.PROJECTILE_PROTECTION;
            case "thorns" -> Enchantment.THORNS;
            case "respiration" -> Enchantment.RESPIRATION;
            case "aqua affinity" -> Enchantment.AQUA_AFFINITY;
            case "depth strider" -> Enchantment.DEPTH_STRIDER;
            case "frost walker" -> Enchantment.FROST_WALKER;
            case "feather falling" -> Enchantment.FEATHER_FALLING;
            case "soul speed" -> Enchantment.SOUL_SPEED;
            case "swift sneak" -> Enchantment.SWIFT_SNEAK;
            case "sharpness" -> Enchantment.SHARPNESS;
            case "smite" -> Enchantment.SMITE;
            case "bane of arthropods" -> Enchantment.BANE_OF_ARTHROPODS;
            case "knockback" -> Enchantment.KNOCKBACK;
            case "fire aspect" -> Enchantment.FIRE_ASPECT;
            case "looting" -> Enchantment.LOOTING;
            case "sweeping edge" -> Enchantment.SWEEPING_EDGE;
            case "efficiency" -> Enchantment.EFFICIENCY;
            case "silk touch" -> Enchantment.SILK_TOUCH;
            case "fortune" -> Enchantment.FORTUNE;
            case "unbreaking" -> Enchantment.UNBREAKING;
            case "mending" -> Enchantment.MENDING;
            case "curse of vanishing" -> Enchantment.VANISHING_CURSE;
            case "curse of binding" -> Enchantment.BINDING_CURSE;
            case "power" -> Enchantment.POWER;
            case "punch" -> Enchantment.PUNCH;
            case "flame" -> Enchantment.FLAME;
            case "infinity" -> Enchantment.INFINITY;
            case "quick charge" -> Enchantment.QUICK_CHARGE;
            case "multishot" -> Enchantment.MULTISHOT;
            case "piercing" -> Enchantment.PIERCING;
            case "luck of the sea" -> Enchantment.LUCK_OF_THE_SEA;
            case "lure" -> Enchantment.LURE;
            case "impaling" -> Enchantment.IMPALING;
            case "riptide" -> Enchantment.RIPTIDE;
            case "loyalty" -> Enchantment.LOYALTY;
            case "channeling" -> Enchantment.CHANNELING;
            default -> null;
        };

        if (ench == null)
            return null;
        return new EnchantmentData(ench, level);
    }
}

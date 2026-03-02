package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.utils.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.inventory.InventoryClickEvent;

public class OrderCategoryGUI extends GUIHolder {

    private final AurelEconomy plugin;
    private final Player player;
    private final Inventory inventory;

    public OrderCategoryGUI(AurelEconomy plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 45,
                Component.text("Select Order Category", NamedTextColor.DARK_GRAY));
        plugin.addViewer(player);
    }

    public void open() {
        setupItems();
        player.openInventory(inventory);
    }

    private void setupItems() {
        // Row 1 (slots 10-16)
        inventory.setItem(10, new ItemBuilder(Material.DIAMOND_SWORD)
                .name(Component.text("Tools & Combat", NamedTextColor.RED, TextDecoration.BOLD)).build());
        inventory.setItem(11, new ItemBuilder(Material.GRASS_BLOCK)
                .name(Component.text("Blocks & Nature", NamedTextColor.GREEN, TextDecoration.BOLD)).build());
        inventory.setItem(12, new ItemBuilder(Material.OAK_LOG)
                .name(Component.text("Wood", NamedTextColor.GOLD, TextDecoration.BOLD)).build());
        inventory.setItem(13, new ItemBuilder(Material.IRON_INGOT)
                .name(Component.text("Minerals", NamedTextColor.AQUA, TextDecoration.BOLD)).build());
        inventory.setItem(14, new ItemBuilder(Material.WHEAT)
                .name(Component.text("Farming", NamedTextColor.YELLOW, TextDecoration.BOLD)).build());
        inventory.setItem(15, new ItemBuilder(Material.BONE)
                .name(Component.text("Mob Drops", NamedTextColor.WHITE, TextDecoration.BOLD)).build());
        inventory.setItem(16, new ItemBuilder(Material.REDSTONE)
                .name(Component.text("Redstone", NamedTextColor.RED, TextDecoration.BOLD)).build());

        // Row 2 (slots 19-25)
        inventory.setItem(19, new ItemBuilder(Material.BRICKS)
                .name(Component.text("Building", NamedTextColor.GRAY, TextDecoration.BOLD)).build());
        inventory.setItem(20, new ItemBuilder(Material.COPPER_BLOCK)
                .name(Component.text("Copper", NamedTextColor.GOLD, TextDecoration.BOLD)).build());
        inventory.setItem(21, new ItemBuilder(Material.PAINTING)
                .name(Component.text("Nether & End", NamedTextColor.DARK_PURPLE, TextDecoration.BOLD)).build());
        inventory.setItem(22, new ItemBuilder(Material.WHITE_WOOL)
                .name(Component.text("COLORS", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)).build());
        inventory.setItem(23, new ItemBuilder(Material.SPAWNER)
                .name(Component.text("Spawners", NamedTextColor.DARK_RED, TextDecoration.BOLD)).build());
        inventory.setItem(24, new ItemBuilder(Material.ENCHANTED_BOOK)
                .name(Component.text("Enchantments", NamedTextColor.DARK_AQUA, TextDecoration.BOLD)).build());

        // Feature Button
        inventory.setItem(31, new ItemBuilder(Material.COMPASS)
                .name(Component.text("Search All Items", NamedTextColor.AQUA, TextDecoration.BOLD))
                .lore(Component.text("Browse or search the entire Minecraft catalog", NamedTextColor.GRAY)).build());

        // Bottom
        inventory.setItem(40,
                new ItemBuilder(Material.BARRIER).name(Component.text("Back", NamedTextColor.RED)).build());
    }

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null)
            return;

        int slot = event.getRawSlot();
        if (slot == 40) {
            new OrdersGUI(plugin, player, 0).open();
            return;
        }

        com.aureleconomy.market.MarketItems.Category cat = null;
        if (slot == 10)
            cat = com.aureleconomy.market.MarketItems.Category.TOOLS_WEAPONS;
        else if (slot == 11)
            cat = com.aureleconomy.market.MarketItems.Category.NATURE;
        else if (slot == 12)
            cat = com.aureleconomy.market.MarketItems.Category.WOOD;
        else if (slot == 13)
            cat = com.aureleconomy.market.MarketItems.Category.MINERALS_ORES;
        else if (slot == 14)
            cat = com.aureleconomy.market.MarketItems.Category.FOOD_FARMING;
        else if (slot == 15)
            cat = com.aureleconomy.market.MarketItems.Category.MOB_DROPS;
        else if (slot == 16)
            cat = com.aureleconomy.market.MarketItems.Category.REDSTONE;
        else if (slot == 19)
            cat = com.aureleconomy.market.MarketItems.Category.BUILDING;
        else if (slot == 20)
            cat = com.aureleconomy.market.MarketItems.Category.COPPER;
        else if (slot == 21)
            cat = com.aureleconomy.market.MarketItems.Category.DECORATION;
        else if (slot == 22)
            cat = com.aureleconomy.market.MarketItems.Category.COLORS;
        else if (slot == 23)
            cat = com.aureleconomy.market.MarketItems.Category.SPAWNERS;
        else if (slot == 24)
            cat = com.aureleconomy.market.MarketItems.Category.ENCHANTMENTS;
        else if (slot == 31)
            cat = com.aureleconomy.market.MarketItems.Category.ALL_ITEMS;

        if (cat != null) {
            new OrderMaterialGUI(plugin, player, cat.name(), 0).open();
        }
    }
}

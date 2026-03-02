package com.aureleconomy.utils;

import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class ItemBuilder {

    private final ItemStack item;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material);
    }

    public ItemBuilder(Material material, int amount) {
        this.item = new ItemStack(material, amount);
    }

    public ItemBuilder(ItemStack item) {
        this.item = item.clone();
    }

    public ItemBuilder name(Component name) {
        if (name == null) {
            return this;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            item.setItemMeta(meta);
        }
        return this;
    }

    public ItemBuilder lore(Component... lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return this;
        List<Component> currentLore = meta.lore();
        if (currentLore == null) {
            currentLore = new java.util.ArrayList<>();
        }
        currentLore.addAll(Arrays.asList(lore));
        meta.lore(currentLore);
        item.setItemMeta(meta);
        return this;
    }

    public ItemBuilder lore(List<Component> lore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null)
            return this;
        List<Component> currentLore = meta.lore();
        if (currentLore == null) {
            currentLore = new java.util.ArrayList<>();
        }
        currentLore.addAll(lore);
        meta.lore(currentLore);
        item.setItemMeta(meta);
        return this;
    }

    public ItemStack build() {
        return item;
    }
}

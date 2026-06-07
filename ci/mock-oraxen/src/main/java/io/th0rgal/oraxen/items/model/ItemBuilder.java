package io.th0rgal.oraxen.items.model;

import io.th0rgal.oraxen.api.OraxenItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class ItemBuilder {
    private final OraxenItem oraxenItem;

    public ItemBuilder(OraxenItem oraxenItem) {
        this.oraxenItem = oraxenItem;
    }

    public ItemStack build() {
        Material material;
        try {
            material = Material.valueOf(oraxenItem.getMaterial());
        } catch (IllegalArgumentException e) {
            material = Material.DIAMOND;
        }
        return new ItemStack(material);
    }
}
package net.indyuce.mmoitems.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class MMOItem {
    private final String id;
    private final Type type;

    public MMOItem(String id, Type type) {
        this.id = id;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    // Method expected by scanner
    public ItemStack newItemStack() {
        Material material;
        try {
            material = Material.valueOf(type.getId().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Default to diamond sword for weapons, diamond pickaxe for tools
            if ("SWORD".equalsIgnoreCase(type.getId()) || "AXE".equalsIgnoreCase(type.getId())) {
                material = Material.DIAMOND_SWORD;
            } else {
                material = Material.DIAMOND_PICKAXE;
            }
        }
        return new ItemStack(material);
    }
}
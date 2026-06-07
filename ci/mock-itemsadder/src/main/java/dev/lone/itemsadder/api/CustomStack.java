package dev.lone.itemsadder.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

public class CustomStack {
    private final String id;
    private final String namespacedId;
    private final ItemStack itemStack;

    private static final Map<String, CustomStack> REGISTRY = new HashMap<>();

    public CustomStack(String id) {
        this(id, Material.DIAMOND);
    }

    public CustomStack(String id, Material material) {
        this.id = id;
        this.namespacedId = "itemsadder:" + id;
        this.itemStack = new ItemStack(material);
        REGISTRY.put(id.toLowerCase(), this);
    }

    public String getNamespacedId() {
        return namespacedId;
    }

    public String getNamespacedID() {
        return namespacedId;
    }

    public ItemStack getItemStack() {
        return itemStack.clone();
    }

    public boolean isSimilar(CustomStack other) {
        return other != null && this.namespacedId.equals(other.namespacedId);
    }

    // Static API methods expected by scanner
    public static Map<String, CustomStack> getItems() {
        return REGISTRY;
    }

    public static CustomStack byItemStack(ItemStack itemStack) {
        if (itemStack == null || !itemStack.hasItemMeta()) return null;
        for (CustomStack stack : REGISTRY.values()) {
            if (stack.getItemStack().isSimilar(itemStack)) {
                return stack;
            }
        }
        return null;
    }

    // Bootstrap method to register default items
    public static void registerDefaults() {
        new CustomStack("ruby_sword", Material.DIAMOND_SWORD);
        new CustomStack("sapphire_pickaxe", Material.DIAMOND_PICKAXE);
        new CustomStack("emerald_axe", Material.DIAMOND_AXE);
    }
}
package io.th0rgal.oraxen.api;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class OraxenItems {
    private static OraxenItems instance;
    private final Map<String, OraxenItem> items = new HashMap<>();

    public OraxenItems() {
        instance = this;
    }

    public static OraxenItems getInstance() {
        return instance;
    }

    public void registerItem(String id, OraxenItem item) {
        items.put(id.toLowerCase(), item);
    }

    public OraxenItem getItem(String id) {
        return items.get(id.toLowerCase());
    }

    public boolean hasItem(String key) {
        return items.containsKey(key.toLowerCase());
    }

    // Static API methods expected by scanner
    public static Set<String> getItems() {
        if (instance == null) return new HashSet<>();
        return new HashSet<>(instance.items.keySet());
    }

    public static ItemBuilder getItemById(String id) {
        if (instance == null) return null;
        OraxenItem item = instance.items.get(id.toLowerCase());
        if (item == null) return null;
        return new ItemBuilder(item);
    }

    // Bootstrap method to register default items
    public static void registerDefaults() {
        if (instance == null) new OraxenItems();
        instance.registerItem("ruby_blade", new OraxenItem("ruby_blade", "DIAMOND_SWORD"));
        instance.registerItem("sapphire_pickaxe", new OraxenItem("sapphire_pickaxe", "DIAMOND_PICKAXE"));
        instance.registerItem("emerald_axe", new OraxenItem("emerald_axe", "DIAMOND_AXE"));
    }
}
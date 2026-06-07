package net.indyuce.mmoitems;

import net.indyuce.mmoitems.api.Type;
import net.indyuce.mmoitems.manager.ItemManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class MMOItems extends JavaPlugin {
    private static MMOItems instance;
    private final ItemManager itemManager;
    private final Map<String, Object> items = new HashMap<>();

    public MMOItems() {
        this.itemManager = new ItemManager();
        instance = this;
    }

    public static MMOItems getPlugin() {
        return instance;
    }

    public ItemManager getItems() {
        return itemManager;
    }

    public Object getItem(String id) {
        return items.get(id.toLowerCase());
    }

    public boolean hasItem(String key) {
        return items.containsKey(key.toLowerCase());
    }

    // Bootstrap method to register default items
    public static void registerDefaults() {
        if (instance == null) new MMOItems();
        ItemManager itemManager = instance.getItems();
        
        // Register types
        Type swordType = new Type("SWORD", "weapon");
        Type pickaxeType = new Type("PICKAXE", "tool");
        Type axeType = new Type("AXE", "tool");
        
        // Register items
        itemManager.register(new MMOItem("RUBY_BLADE", swordType));
        itemManager.register(new MMOItem("SAPPHIRE_PICKAXE", pickaxeType));
        itemManager.register(new MMOItem("EMERALD_AXE", axeType));
    }

    @Override
    public void onEnable() {
        registerDefaults();
        getLogger().info("MockMMOItems enabled with " + itemManager.getAllTypes().size() + " types!");
    }

    @Override
    public void onDisable() {
        getLogger().info("MockMMOItems disabled!");
    }
}
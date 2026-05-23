package mock;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockItemsAdder extends JavaPlugin {

    private static final Map<String, MockItem> items = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        // Register 3 mock custom items that Aurelium's scanner should detect
        items.put("itemsadder:ruby_sword", new MockItem("itemsadder:ruby_sword",
            org.bukkit.Material.DIAMOND_SWORD, "Ruby Sword", 10001));
        items.put("itemsadder:emerald_pickaxe", new MockItem("itemsadder:emerald_pickaxe",
            org.bukkit.Material.DIAMOND_PICKAXE, "Emerald Pickaxe", 10002));
        items.put("itemsadder:sapphire_helmet", new MockItem("itemsadder:sapphire_helmet",
            org.bukkit.Material.DIAMOND_HELMET, "Sapphire Helmet", 10003));

        getLogger().info("MockItemsAdder loaded with " + items.size() + " custom items");
    }

    public static Map<String, MockItem> getItems() {
        return items;
    }
}

package mock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockItemsAdder extends JavaPlugin {

    private static final Map<String, CustomStack> items = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        // Register 3 mock custom items
        items.put("itemsadder:ruby_sword", new CustomStack("itemsadder:ruby_sword",
            Material.DIAMOND_SWORD, "Ruby Sword", 10001));
        items.put("itemsadder:emerald_pickaxe", new CustomStack("itemsadder:emerald_pickaxe",
            Material.DIAMOND_PICKAXE, "Emerald Pickaxe", 10002));
        items.put("itemsadder:sapphire_helmet", new CustomStack("itemsadder:sapphire_helmet",
            Material.DIAMOND_HELMET, "Sapphire Helmet", 10003));

        getLogger().info("MockItemsAdder loaded with " + items.size() + " custom items");
    }

    public static Map<String, CustomStack> getItems() {
        return items;
    }
}

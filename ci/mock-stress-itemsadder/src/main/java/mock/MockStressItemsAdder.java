package mock;

import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockStressItemsAdder extends JavaPlugin {

 private static final Map<String, MockItem> items = new LinkedHashMap<>();

 @Override
 public void onEnable() {
 for (int i = 1; i <= 500; i++) {
 String id = String.format("itemsadder:stress_item_%03d", i);
 items.put(id, new MockItem(id,
 Material.DIAMOND_SWORD, "Stress Item " + i, 10000 + i));
 }
 getLogger().info("ItemsAdder (stress mock) loaded with " + items.size() + " custom items");
 }

 public static Map<String, MockItem> getItems() {
 return items;
 }
}

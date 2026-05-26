package mock;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class MockSXItem extends JavaPlugin {

 private static final List<SXItemData> items = new ArrayList<>();

 @Override
 public void onEnable() {
 items.add(new SXItemData("sxitem:fire_sword",
 org.bukkit.Material.DIAMOND_SWORD, "Fire Sword", 60001));
 items.add(new SXItemData("sxitem:ice_pick",
 org.bukkit.Material.DIAMOND_PICKAXE, "Ice Pick", 60002));

 getLogger().info("SX-Item (mock) loaded with " + items.size() + " custom items");
 }

 public static List<SXItemData> getItemList() {
 return items;
 }
}

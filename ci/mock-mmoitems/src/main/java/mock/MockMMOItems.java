package mock;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockMMOItems extends JavaPlugin {

 private static final Map<String, MockMMOItem> items = new LinkedHashMap<>();

 @Override
 public void onEnable() {
 // CRITICAL: SWORD:RUBY_BLADE uses CMD 10001 — same as ItemsAdder's ruby_sword
 // This creates a 3-way overlap for dedup testing
 items.put("SWORD:RUBY_BLADE", new MockMMOItem("SWORD", "RUBY_BLADE",
 org.bukkit.Material.DIAMOND_SWORD, "MMO Ruby Blade", 10001));
 items.put("PICKAXE:VOID_PICK", new MockMMOItem("PICKAXE", "VOID_PICK",
 org.bukkit.Material.DIAMOND_PICKAXE, "Void Pickaxe", 30001));

 getLogger().info("MMOItems (mock) loaded with " + items.size() + " custom items");
 }

 public static Map<String, MockMMOItem> getItems() {
 return items;
 }
}

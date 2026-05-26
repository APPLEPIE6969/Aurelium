package mock;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockOraxen extends JavaPlugin {

 private static final Map<String, MockOraxenItem> items = new LinkedHashMap<>();

 @Override
 public void onEnable() {
 // CRITICAL: ruby_blade uses CMD 10001 — same as ItemsAdder's ruby_sword
 // This tests cross-plugin dedup via modelDataKey
 items.put("oraxen:ruby_blade", new MockOraxenItem("oraxen:ruby_blade",
 org.bukkit.Material.DIAMOND_SWORD, "Ruby Blade", 10001, "Oraxen"));
 items.put("oraxen:obsidian_axe", new MockOraxenItem("oraxen:obsidian_axe",
 org.bukkit.Material.DIAMOND_AXE, "Obsidian Axe", 20002, "Oraxen"));

 getLogger().info("Oraxen (mock) loaded with " + items.size() + " custom items");
 }

 public static Map<String, MockOraxenItem> getItems() {
 return items;
 }
}

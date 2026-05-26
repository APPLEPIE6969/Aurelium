package mock;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class MockMythicMobs extends JavaPlugin {

 private static final Map<String, org.bukkit.inventory.ItemStack> items = new LinkedHashMap<>();

 @Override
 public void onEnable() {
 items.put("mythicmobs:dragon_fang", createItem(
 org.bukkit.Material.DIAMOND_SWORD, "Dragon Fang", 40001));
 items.put("mythicmobs:phoenix_bow", createItem(
 org.bukkit.Material.BOW, "Phoenix Bow", 40002));

 getLogger().info("MythicMobs (mock) loaded with " + items.size() + " custom items");
 }

 public static Map<String, org.bukkit.inventory.ItemStack> getItems() {
 return items;
 }

 private org.bukkit.inventory.ItemStack createItem(org.bukkit.Material material, String displayName, int cmd) {
 org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material);
 org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
 meta.displayName(net.kyori.adventure.text.Component.text(displayName));
 meta.setCustomModelData(cmd);
 meta.lore(java.util.List.of(net.kyori.adventure.text.Component.text("MythicMobs item")));
 item.setItemMeta(meta);
 return item;
 }
}

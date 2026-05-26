package mock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import java.util.List;

public class MockMMOItem {

 private final String type;
 private final String id;
 private final ItemStack itemStack;

 public MockMMOItem(String type, String id, Material material, String displayName, int customModelData) {
 this.type = type;
 this.id = id;
 this.itemStack = new ItemStack(material);
 ItemMeta meta = itemStack.getItemMeta();
 meta.displayName(Component.text(displayName));
 meta.setCustomModelData(customModelData);
 meta.lore(List.of(Component.text("Custom item from MMOItems")));
 itemStack.setItemMeta(meta);
 }

 public String getType() {
 return type;
 }

 public String getId() {
 return id;
 }

 public String getNativeId() {
 return type + ":" + id;
 }

 public ItemStack getItemStack() {
 return itemStack.clone();
 }
}

package mock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import java.util.List;

public class MockOraxenItem {

 private final String namespacedId;
 private final ItemStack itemStack;

 public MockOraxenItem(String namespacedId, Material material, String displayName, int customModelData, String loreLine) {
 this.namespacedId = namespacedId;
 this.itemStack = new ItemStack(material);
 ItemMeta meta = itemStack.getItemMeta();
 meta.displayName(Component.text(displayName));
 meta.setCustomModelData(customModelData);
 meta.lore(List.of(Component.text(loreLine)));
 itemStack.setItemMeta(meta);
 }

 public String getNamespacedId() {
 return namespacedId;
 }

 public ItemStack getItemStack() {
 return itemStack.clone();
 }
}

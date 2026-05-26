package mock;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

public class MockPDCGiver extends JavaPlugin implements Listener {

 @Override
 public void onEnable() {
 getServer().getPluginManager().registerEvents(this, this);
 getLogger().info("PDC-Giver (mock) loaded");
 }

 @EventHandler
 public void onPlayerJoin(PlayerJoinEvent event) {
 givePDCItem(event.getPlayer());
 }

 private void givePDCItem(Player player) {
 ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
 ItemMeta meta = item.getItemMeta();
 meta.displayName(Component.text("Cursed Blade"));
 meta.setCustomModelData(70001);
 NamespacedKey key = new NamespacedKey("test", "custom_item_id");
 meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, "test:cursed_blade");
 meta.lore(java.util.List.of(Component.text("A cursed blade")));
 item.setItemMeta(meta);
 player.getInventory().addItem(item);
 getLogger().info("Gave PDC item to " + player.getName());
 }
}

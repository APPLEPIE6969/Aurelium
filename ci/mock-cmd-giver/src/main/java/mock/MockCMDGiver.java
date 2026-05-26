package mock;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

public class MockCMDGiver extends JavaPlugin implements Listener {

 @Override
 public void onEnable() {
 getServer().getPluginManager().registerEvents(this, this);
 getLogger().info("CMD-Giver (mock) loaded");
 }

 @EventHandler
 public void onPlayerJoin(PlayerJoinEvent event) {
 giveCMDItem(event.getPlayer());
 }

 private void giveCMDItem(Player player) {
 ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
 ItemMeta meta = item.getItemMeta();
 meta.displayName(Component.text("Custom Sword"));
 meta.setCustomModelData(99999);
 meta.lore(java.util.List.of(Component.text("Custom Item")));
 item.setItemMeta(meta);
 player.getInventory().addItem(item);
 getLogger().info("Gave CMD item to " + player.getName());
 }
}

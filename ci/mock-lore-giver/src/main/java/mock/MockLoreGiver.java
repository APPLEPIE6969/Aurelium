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
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

public class MockLoreGiver extends JavaPlugin implements Listener {

 @Override
 public void onEnable() {
 getServer().getPluginManager().registerEvents(this, this);
 getLogger().info("Lore-Giver (mock) loaded");
 }

 @EventHandler
 public void onPlayerJoin(PlayerJoinEvent event) {
 giveLoreItem(event.getPlayer());
 }

 private void giveLoreItem(Player player) {
 ItemStack item = new ItemStack(Material.IRON_SWORD);
 ItemMeta meta = item.getItemMeta();
 meta.displayName(Component.text("Hex Blade"));
 // No PDC, no CustomModelData — only lore pattern
 meta.lore(java.util.List.of(
 Component.text("\u00a7x\u00a7a\u00a7b\u00a7c\u00a7d\u00a7e\u00a7fHex Item"),
 Component.text("ItemsAdder")
 ));
 item.setItemMeta(meta);
 player.getInventory().addItem(item);
 getLogger().info("Gave lore-pattern item to " + player.getName());
 }
}

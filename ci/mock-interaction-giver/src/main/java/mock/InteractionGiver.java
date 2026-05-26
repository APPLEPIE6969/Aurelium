package mock;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Mock plugin that gives players custom items on join so interaction-detect
 * can detect them when players interact with them during CI testing.
 */
public class InteractionGiver extends JavaPlugin implements Listener {

    public static final NamespacedKey INTERACT_KEY = new NamespacedKey("interactiongiver", "item-id");

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("InteractionGiver (mock) loaded");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Give a custom item that the player can interact with
        player.getInventory().addItem(createInteractItem());
        getLogger().info("Gave interaction test item to " + player.getName());
    }

    private ItemStack createInteractItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Interact Test Wand"));
        meta.setCustomModelData(40001);
        meta.getPersistentDataContainer().set(INTERACT_KEY, PersistentDataType.STRING, "test_wand");
        meta.lore(List.of(Component.text("Custom Item"), Component.text("ID: test_wand")));
        item.setItemMeta(meta);
        return item;
    }
}

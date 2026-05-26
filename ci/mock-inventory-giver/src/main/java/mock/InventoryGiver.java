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
 * Mock plugin that gives players custom items on join so inventory-scan
 * can detect them. Items use PDC, CMD, and lore patterns that Aurelium
 * scanner should detect.
 */
public class InventoryGiver extends JavaPlugin implements Listener {

    public static final NamespacedKey PDC_KEY = new NamespacedKey("inventorygiver", "custom-item");

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("InventoryGiver (mock) loaded");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Give 3 custom items with different detection methods
        player.getInventory().addItem(createPdcItem());
        player.getInventory().addItem(createCmdItem());
        player.getInventory().addItem(createLoreItem());
        getLogger().info("Gave 3 custom items to " + player.getName());
    }

    private ItemStack createPdcItem() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("PDC Custom Sword"));
        meta.getPersistentDataContainer().set(PDC_KEY, PersistentDataType.STRING, "pdc_sword");
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCmdItem() {
        ItemStack item = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("CMD Custom Pick"));
        meta.setCustomModelData(30001);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createLoreItem() {
        ItemStack item = new ItemStack(Material.DIAMOND_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Lore Custom Axe"));
        meta.lore(List.of(Component.text("Custom Item"), Component.text("ID: lore_axe")));
        item.setItemMeta(meta);
        return item;
    }
}

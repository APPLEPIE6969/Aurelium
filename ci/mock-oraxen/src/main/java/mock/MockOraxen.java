package mock;

import io.th0rgal.oraxen.api.OraxenItems;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class MockOraxen extends JavaPlugin implements Listener {
    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        OraxenItems.registerDefaults();
        getLogger().info("MockOraxen enabled with " + OraxenItems.getItems().size() + " items!");
    }

    @Override
    public void onDisable() {
        getLogger().info("MockOraxen disabled!");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getPlayer() instanceof Player) {
            event.getPlayer().sendMessage("§7[MockOraxen] Interacted!");
        }
    }
}
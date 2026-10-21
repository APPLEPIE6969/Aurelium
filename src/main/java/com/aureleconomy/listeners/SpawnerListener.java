package com.aureleconomy.listeners;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.function.Predicate;

public class SpawnerListener implements Listener {

    /**
     * H8: the permission that turns a mined spawner into the plugin's spawner
     * item. Without it the event is left to vanilla, which drops a plain
     * spawner only when the tool has Silk Touch.
     */
    public static final String MINE_PERMISSION = "aureleconomy.spawner-mine";

    private final AurelEconomy plugin;
    private final Predicate<ItemStack> silkTouchCheck;

    public SpawnerListener(AurelEconomy plugin) {
        this(plugin, tool -> tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0);
    }

    /**
     * Test seam. {@code Enchantment.SILK_TOUCH} is registry-backed on modern
     * Paper and cannot be reached without a server, so the check is injected
     * rather than looked up inline. Production always uses the constructor above.
     */
    SpawnerListener(AurelEconomy plugin, Predicate<ItemStack> silkTouchCheck) {
        this.plugin = plugin;
        this.silkTouchCheck = silkTouchCheck;
    }

    /**
     * H8: this used to run at NORMAL priority, before a protection plugin could
     * cancel the event, accept any pickaxe, and never check Silk Touch or a
     * permission. The result was a typed-spawner dupe behind any region flag
     * that only cancels the event, and a mob-type loss for players mining
     * their own spawners with a plain pickaxe.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawnerBreak(BlockBreakEvent event) {
        if (event.isCancelled())
            return;

        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER)
            return;

        Player player = event.getPlayer();
        if (!player.hasPermission(MINE_PERMISSION)) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!tool.getType().name().contains("PICKAXE")) {
            return;
        }

        if (plugin.getConfig().getBoolean("spawners.require-silk-touch", true) && !silkTouchCheck.test(tool)) {
            return;
        }

        BlockState state = block.getState();
        if (!(state instanceof CreatureSpawner))
            return;

        CreatureSpawner spawner = (CreatureSpawner) state;
        EntityType type = spawner.getSpawnedType();

        ItemStack drop = new ItemStack(Material.SPAWNER);
        BlockStateMeta meta = (BlockStateMeta) drop.getItemMeta();
        CreatureSpawner metaSpawner = (CreatureSpawner) meta.getBlockState();

        metaSpawner.setSpawnedType(type);
        meta.setBlockState(metaSpawner);

        String name = formatName(type);
        meta.displayName(Component.text(name + " Spawner"));

        drop.setItemMeta(meta);

        block.getWorld().dropItemNaturally(block.getLocation(), drop);
        event.setExpToDrop(0);
        event.setDropItems(false);
    }

    @EventHandler
    public void onSpawnerPlace(BlockPlaceEvent event) {
        if (event.isCancelled())
            return;

        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.SPAWNER)
            return;

        Block block = event.getBlockPlaced();
        BlockState state = block.getState();

        if (state instanceof CreatureSpawner) {
            CreatureSpawner spawner = (CreatureSpawner) state;

            if (item.getItemMeta() instanceof BlockStateMeta) {
                BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
                if (meta.getBlockState() instanceof CreatureSpawner) {
                    CreatureSpawner metaSpawner = (CreatureSpawner) meta.getBlockState();
                    spawner.setSpawnedType(metaSpawner.getSpawnedType());
                }
            }

            spawner.update();
        }
    }

    private String formatName(EntityType type) {
        if (type == null)
            return "Unknown";
        String name = type.name().toLowerCase().replace("_", " ");
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }
}

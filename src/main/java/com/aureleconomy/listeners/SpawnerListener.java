package com.aureleconomy.listeners;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

public class SpawnerListener implements Listener {

    public SpawnerListener(AurelEconomy plugin) {
        // Plugin parameter kept for future use if needed, but field removed to fix
        // warning
    }

    @EventHandler
    public void onSpawnerBreak(BlockBreakEvent event) {
        if (event.isCancelled())
            return;

        Block block = event.getBlock();
        if (block.getType() != Material.SPAWNER)
            return;

        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();

        if (!tool.getType().name().contains("PICKAXE")) {
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

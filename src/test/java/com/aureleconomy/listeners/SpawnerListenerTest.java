package com.aureleconomy.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aureleconomy.AurelEconomy;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

/**
 * H8: mining a spawner must need the permission and, unless the owner turns the
 * setting off, Silk Touch, and the listener must not act on an event a
 * protection plugin already cancelled.
 *
 * <p>The old listener accepted any pickaxe, never checked a permission, and ran
 * at NORMAL priority, so a region plugin that only cancels the event still let
 * the spawner item out. Each case here fails on that listener: the no-silk and
 * no-permission cases see a drop, and the annotation case sees the wrong
 * priority.
 *
 * <p>The Silk Touch check is injected because {@code Enchantment} itself is
 * registry-backed on this API and cannot be loaded in a unit test. The
 * production constructor's one-line lambda is covered by a static check.
 */
class SpawnerListenerTest {

    private AurelEconomy plugin;
    private YamlConfiguration config;
    private boolean silkTouch;
    private SpawnerListener listener;
    private Block block;
    private World world;
    private Location location;
    private Player player;
    private PlayerInventory inventory;
    private ItemStack tool;
    private CreatureSpawner state;

    @BeforeEach
    void setUp() {
        config = new YamlConfiguration();
        plugin = mock(AurelEconomy.class);
        when(plugin.getConfig()).thenReturn(config);
        listener = new SpawnerListener(plugin, item -> silkTouch);

        location = mock(Location.class);
        world = mock(World.class);
        block = mock(Block.class);
        when(block.getType()).thenReturn(Material.SPAWNER);
        when(block.getWorld()).thenReturn(world);
        when(block.getLocation()).thenReturn(location);
        state = mock(CreatureSpawner.class);
        when(state.getSpawnedType()).thenReturn(EntityType.ZOMBIE);
        when(block.getState()).thenReturn(state);

        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(player.hasPermission(SpawnerListener.MINE_PERMISSION)).thenReturn(true);
        tool = mock(ItemStack.class);
        when(tool.getType()).thenReturn(Material.DIAMOND_PICKAXE);
        when(inventory.getItemInMainHand()).thenReturn(tool);
    }

    @Test
    @DisplayName("a pickaxe without Silk Touch drops nothing")
    void noSilkTouchDropsNothing() {
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onSpawnerBreak(event);

        verify(world, never()).dropItemNaturally(any(), any());
        assertTrue(event.isDropItems(), "the event was changed even though nothing dropped");
    }

    @Test
    @DisplayName("Silk Touch without the permission drops nothing")
    void noPermissionDropsNothing() {
        silkTouch = true;
        when(player.hasPermission(SpawnerListener.MINE_PERMISSION)).thenReturn(false);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onSpawnerBreak(event);

        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("a cancelled event is left alone")
    void cancelledEventDropsNothing() {
        silkTouch = true;
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(true);

        listener.onSpawnerBreak(event);

        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("a non-pickaxe drops nothing")
    void nonPickaxeDropsNothing() {
        silkTouch = true;
        when(tool.getType()).thenReturn(Material.STICK);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        listener.onSpawnerBreak(event);

        verify(world, never()).dropItemNaturally(any(), any());
    }

    @Test
    @DisplayName("Silk Touch plus the permission drops the spawner with its mob type")
    void silkTouchAndPermissionDropsTheTypedSpawner() {
        silkTouch = true;
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        BlockStateMeta meta = mock(BlockStateMeta.class);
        CreatureSpawner metaSpawner = mock(CreatureSpawner.class);
        when(meta.getBlockState()).thenReturn(metaSpawner);

        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class,
                (mock, context) -> when(mock.getItemMeta()).thenReturn(meta))) {
            listener.onSpawnerBreak(event);

            ItemStack drop = construction.constructed().get(0);
            verify(metaSpawner).setSpawnedType(EntityType.ZOMBIE);
            verify(meta).setBlockState(metaSpawner);
            verify(meta).displayName(any());
            verify(drop).setItemMeta(meta);
            verify(world).dropItemNaturally(same(location), same(drop));
            assertFalse(event.isDropItems(), "the vanilla drop was not suppressed, so two spawners could drop");
        }
    }

    @Test
    @DisplayName("with the Silk Touch requirement off, any pickaxe drops the spawner")
    void silkTouchRequirementCanBeTurnedOff() {
        config.set("spawners.require-silk-touch", false);
        BlockBreakEvent event = new BlockBreakEvent(block, player);

        BlockStateMeta meta = mock(BlockStateMeta.class);
        when(meta.getBlockState()).thenReturn(mock(CreatureSpawner.class));

        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class,
                (mock, context) -> when(mock.getItemMeta()).thenReturn(meta))) {
            listener.onSpawnerBreak(event);
            assertEquals(1, construction.constructed().size());
        }
    }

    @Test
    @DisplayName("the break handler runs last and only on events nobody cancelled")
    void handlerRunsAtHighestAndIgnoresCancelled() throws Exception {
        EventHandler handler = SpawnerListener.class
                .getMethod("onSpawnerBreak", BlockBreakEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, handler.priority(),
                "NORMAL runs before a protection plugin can cancel the event");
        assertTrue(handler.ignoreCancelled(), "a cancelled event must never reach the drop");
    }
}

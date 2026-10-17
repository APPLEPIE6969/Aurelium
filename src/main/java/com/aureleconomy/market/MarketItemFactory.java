package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Builds the ItemStack a market entry actually represents.
 *
 * <p>A market entry is not always its material. Every spawner is
 * {@link Material#SPAWNER}, so the mob type has to be written into the block
 * state, and every enchanted book is {@link Material#ENCHANTED_BOOK}, so the
 * stored enchant has to be applied. An entry with a custom name is therefore not
 * interchangeable with a plain stack of its material.
 *
 * <p>This lived as a private method on ShopGUI, which meant the two web purchase
 * paths each built their own stack instead and handed a player a bare spawner or
 * an empty book while charging the custom price. The one implementation is here
 * so the in-game and web paths cannot drift again.
 */
public final class MarketItemFactory {

    private MarketItemFactory() {
    }

    /**
     * The stack for {@code entry}, with the amount left at 1 so the caller can
     * set it explicitly.
     */
    public static ItemStack build(AurelEconomy plugin, MarketItems.MarketEntry entry) {
        if (entry == null) {
            return null;
        }

        if (entry.material == Material.SPAWNER && entry.customName != null) {
            return buildSpawner(plugin, entry);
        }
        if (entry.material == Material.ENCHANTED_BOOK && entry.customName != null) {
            return MarketItems.createEnchantedBook(entry.customName);
        }
        return new ItemStack(entry.material);
    }

    /**
     * The stack for {@code entry} with {@code amount} applied.
     *
     * <p>Preferred over {@link #build} and then {@code setAmount}, because
     * setAmount on a stack that is already at or over the max is silently clamped
     * rather than raising.
     */
    public static ItemStack build(AurelEconomy plugin, MarketItems.MarketEntry entry, int amount) {
        ItemStack stack = build(plugin, entry);
        if (stack == null) {
            return null;
        }
        if (amount == stack.getAmount()) {
            return stack;
        }
        stack.setAmount(amount);
        return stack;
    }

    /**
     * Resolves the entry a price key refers to, or null when the key is not a
     * named entry.
     *
     * <p>Keys for plain items are the material name, which
     * {@link Material#matchMaterial} handles. Named entries are keyed by the
     * material plus the display name, so they have to come from the entry cache.
     */
    public static MarketItems.MarketEntry entryForKey(AurelEconomy plugin, String key) {
        if (plugin == null || key == null) {
            return null;
        }
        return plugin.getMarketManager().getEntryCache().get(key);
    }

    /**
     * The stack for a price key, or null when the key resolves to nothing.
     *
     * <p>Callers must treat null as "refuse this purchase" rather than
     * substituting a default. Falling back to some other item is how a player
     * ends up charged for one thing and handed another.
     *
     * <p>Only the entry cache is consulted. Every market item is cached under the
     * same key its price is stored under, so a key that misses here has no price
     * either and the caller refuses it on price anyway. Guessing a material from
     * the key could therefore only ever reach a refusal, while still being able
     * to hand back an item that is not a market entry at all.
     */
    public static ItemStack buildForKey(AurelEconomy plugin, String key, int amount) {
        MarketItems.MarketEntry entry = entryForKey(plugin, key);
        if (entry == null) {
            return null;
        }
        return build(plugin, entry, amount);
    }

    private static ItemStack buildSpawner(AurelEconomy plugin, MarketItems.MarketEntry entry) {
        ItemStack spawner = new ItemStack(Material.SPAWNER);
        BlockStateMeta meta = (BlockStateMeta) spawner.getItemMeta();
        if (meta == null) {
            return spawner;
        }

        CreatureSpawner state = (CreatureSpawner) meta.getBlockState();
        String mobName = entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
        try {
            state.setSpawnedType(EntityType.valueOf(mobName));
            meta.setBlockState(state);
        } catch (IllegalArgumentException e) {
            // A display name that is not a mob. Still return the spawner with its
            // name set, so the player can see what it is.
            if (plugin != null) {
                plugin.getComponentLogger()
                        .warn("Failed to set spawner type for mob: " + mobName);
            }
        }
        meta.displayName(Component.text(entry.customName, NamedTextColor.AQUA));
        spawner.setItemMeta(meta);
        return spawner;
    }
}
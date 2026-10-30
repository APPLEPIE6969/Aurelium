package com.aureleconomy.market;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Optional;

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

    /**
     * The price key for an existing stack, or null when the market cannot value
     * that stack as itself (M2).
     *
     * <p>This is the read direction of the factory: {@link #build} turns a key into
     * the item it represents, this turns an item back into the key its price is
     * stored under. The old sell GUI guessed this from the material name, which
     * produced {@code Iron Golem Spawner} where the price map holds
     * {@code SPAWNER_IRON_GOLEM_SPAWNER}, so spawners could never be sold at all.
     *
     * <p>Order matters:
     * <ol>
     *   <li>A scanner-registered custom item sells as itself, keyed by its
     *       canonical id. A registered item that is switched off resolves to null
     *       rather than to its material, so disabling an item cannot make it
     *       tradeable at a different price (H7).</li>
     *   <li>A named entry (spawners, enchanted books) matches only when the stack
     *       is {@code isSimilar} to the stack the factory would build for it, so a
     *       pig spawner cannot be sold as an iron golem spawner.</li>
     *   <li>A plain material entry accepts the stack only when no display name,
     *       lore, enchantment or custom data would be discarded. Potion contents,
     *       dye colour and book pages are variants of the same material and are
     *       still sold at the material price.</li>
     * </ol>
     *
     * <p>Anything else returns null: the caller must refuse it rather than pay
     * base-material money for an item worth more.
     */
    public static String priceKey(AurelEconomy plugin, ItemStack stack) {
        if (plugin == null || stack == null || stack.getType() == null || stack.getType() == Material.AIR) {
            return null;
        }
        MarketManager market = plugin.getMarketManager();
        if (market == null || market.getEntryCache() == null) {
            return null;
        }

        CustomItemRegistry registry = plugin.getCustomItemRegistry();
        if (registry != null && !registry.isEmpty()) {
            Optional<String> id = registry.resolveItemId(stack);
            if (id.isPresent()) {
                CustomMarketItem custom = registry.getById(id.get());
                if (custom != null) {
                    // A registered custom item is priced as itself or not at all.
                    return custom.isEnabled() && market.getEntryCache().containsKey(id.get()) ? id.get() : null;
                }
            }
        }

        for (Map.Entry<String, MarketItems.MarketEntry> cached : market.getEntryCache().entrySet()) {
            MarketItems.MarketEntry entry = cached.getValue();
            if (entry == null || entry.customName == null || entry.material != stack.getType()) {
                continue;
            }
            // Scanner items are cached under their canonical id, not under entryKey,
            // and their factory stack is a plain one. Only catalogue entries are
            // candidates here, or a plain diamond could match a custom item's name.
            if (!cached.getKey().equals(market.entryKey(entry))) {
                continue;
            }
            ItemStack reference = build(plugin, entry, 1);
            if (reference != null && reference.isSimilar(stack)) {
                return market.entryKey(entry);
            }
        }

        if (!carriesValueMeta(stack)) {
            String key = stack.getType().name();
            if (market.getEntryCache().containsKey(key)) {
                return key;
            }
        }
        return null;
    }

    /**
     * True when the stack carries item meta the market cannot price: a name,
     * lore, enchantments, an explicit attribute, custom model data or
     * unbreakability. Meta that only describes a variant of the same material
     * (potion contents, dye colour, written pages) is not counted, so those keep
     * selling at the material price.
     */
    private static boolean carriesValueMeta(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants()
                || meta.hasCustomModelData() || meta.isUnbreakable()
                || meta.hasAttributeModifiers()) {
            return true;
        }
        return meta instanceof EnchantmentStorageMeta stored && stored.hasStoredEnchants();
    }

    /**
     * How many of {@code expected} an inventory holds, capped at {@code needed}.
     *
     * <p>The web sell route and the sell window both have to answer "is this the
     * same item", and the answer has to be the factory's stack rather than the
     * material. This lived privately in the web executor while the sell GUI had
     * its own material-only version, which is how a plain spawner could be sold
     * as an iron golem spawner.
     */
    public static int countSimilar(Inventory inventory, ItemStack expected, int needed) {
        if (inventory == null || expected == null || needed <= 0) {
            return 0;
        }
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || !stack.isSimilar(expected)) {
                continue;
            }
            total += stack.getAmount();
            if (total >= needed) {
                return needed;
            }
        }
        return total;
    }

    /**
     * Removes exactly {@code amount} of {@code expected} from an inventory,
     * taking only stacks the factory's stack is similar to.
     */
    public static void removeSimilar(Inventory inventory, ItemStack expected, int amount) {
        if (inventory == null || expected == null || amount <= 0) {
            return;
        }
        int left = amount;
        ItemStack[] contents = inventory.getStorageContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || !stack.isSimilar(expected)) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (stack.getAmount() == take) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                inventory.setItem(slot, stack);
            }
        }
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
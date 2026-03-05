package com.aureleconomy.market;

import org.bukkit.Material;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.NamespacedKey;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

public class MarketItems {

        public enum Category {
                TOOLS_WEAPONS(Material.DIAMOND_SWORD, "Tools & Combat"),
                FOOD_FARMING(Material.GOLDEN_CARROT, "Food & Farming"),
                MINERALS_ORES(Material.DIAMOND, "Minerals & Ores"),
                MOB_DROPS(Material.BLAZE_ROD, "Mob Drops & Magic"),
                NATURE(Material.OAK_SAPLING, "Nature & Flora"),
                REDSTONE(Material.REDSTONE, "Redstone & Tech"),
                WOOD(Material.OAK_LOG, "Wood & Planks"),
                COPPER(Material.COPPER_BLOCK, "Copper Blocks"),
                SPAWNERS(Material.SPAWNER, "Spawners"),
                COLORS(Material.WHITE_WOOL, "Colors & Dyes"),
                BUILDING(Material.BRICKS, "Building Blocks"),
                DECORATION(Material.PAINTING, "Decoration"),
                ENCHANTMENTS(Material.ENCHANTED_BOOK, "Enchantment Books"),
                ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");

                public final Material icon;
                public final String name;

                Category(Material icon, String name) {
                        this.icon = icon;
                        this.name = name;
                }
        }

        public static class MarketEntry {
                public final Material material;
                public final double price;
                public final Double customSellPrice;
                public final String customName;

                public MarketEntry(Material material, double price) {
                        this(material, price, null, null);
                }

                public MarketEntry(Material material, double price, double sellPrice) {
                        this(material, price, sellPrice, null);
                }

                public MarketEntry(Material material, double price, String customName) {
                        this(material, price, null, customName);
                }

                public MarketEntry(Material material, double price, Double sellPrice, String customName) {
                        this.material = material;
                        this.price = price;
                        this.customSellPrice = sellPrice;
                        this.customName = customName;
                }
        }

        public static ItemStack createEnchantedBook(String name) {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                EnchantmentStorageMeta meta = (EnchantmentStorageMeta) book.getItemMeta();
                if (meta == null || name == null)
                        return book;

                String[] parts = name.split(" ");
                if (parts.length < 2 && !name.equals("Flame") && !name.equals("Infinity") && !name.equals("Channeling")
                                && !name.equals("Multishot") && !name.equals("Mending"))
                        return book;

                String roman = "";
                String enchName = name;
                int level = 1;

                if (parts.length > 1) {
                        roman = parts[parts.length - 1];
                        if (roman.matches("^[IVX]+$")) {
                                enchName = name.substring(0, name.lastIndexOf(" "));
                                switch (roman) {
                                        case "I":
                                                level = 1;
                                                break;
                                        case "II":
                                                level = 2;
                                                break;
                                        case "III":
                                                level = 3;
                                                break;
                                        case "IV":
                                                level = 4;
                                                break;
                                        case "V":
                                                level = 5;
                                                break;
                                }
                        }
                }

                String bukkitName = enchName.toUpperCase().replace(" ", "_");

                // Handle special cases where Bukkit names differ from in-game names
                if (bukkitName.equals("SWEEPING_EDGE"))
                        bukkitName = "SWEEPING_COLLISION";

                Enchantment ench = RegistryAccess.registryAccess()
                                .getRegistry(RegistryKey.ENCHANTMENT)
                                .get(NamespacedKey.minecraft(bukkitName.toLowerCase()));

                if (ench != null) {
                        meta.addStoredEnchant(ench, level, true);
                }

                book.setItemMeta(meta);
                return book;
        }

        private static final Map<Category, List<MarketEntry>> items = new EnumMap<>(Category.class);

        static {
                for (Category cat : Category.values()) {
                        items.put(cat, new ArrayList<>());
                }

                // Dynamically populate ALL_ITEMS with all survival-obtainable items
                List<MarketEntry> allItemsList = new ArrayList<>();
                for (Material mat : Material.values()) {
                        if (mat.isItem() && isObtainable(mat)) {
                                allItemsList.add(new MarketEntry(mat, 1.0));
                        }
                }
                items.put(Category.ALL_ITEMS, allItemsList);

                // ⚔️ Tools, Weapons & Armor
                add(Category.TOOLS_WEAPONS,
                                // Ranged (Ammo only)
                                new MarketEntry(Material.ARROW, 5), new MarketEntry(Material.SPECTRAL_ARROW, 15),
                                new MarketEntry(Material.TIPPED_ARROW, 25), new MarketEntry(Material.WIND_CHARGE, 100),
                                new MarketEntry(Material.FIREWORK_ROCKET, 20),
                                new MarketEntry(Material.FIREWORK_STAR, 30),
                                new MarketEntry(Material.SNOWBALL, 2),
                                // Utility (Consumables/Misc)
                                new MarketEntry(Material.NAME_TAG, 1000),
                                new MarketEntry(Material.SADDLE, 2000),
                                new MarketEntry(Material.LEAD, 50));

                // 🍗 Food, Farming & Crops
                add(Category.FOOD_FARMING,
                                new MarketEntry(Material.BEEF, 8), new MarketEntry(Material.CHICKEN, 6),
                                new MarketEntry(Material.COD, 5), new MarketEntry(Material.MUTTON, 7),
                                new MarketEntry(Material.PORKCHOP, 8), new MarketEntry(Material.RABBIT, 7),
                                new MarketEntry(Material.SALMON, 6), new MarketEntry(Material.TROPICAL_FISH, 20),
                                new MarketEntry(Material.PUFFERFISH, 40), new MarketEntry(Material.POTATO, 5),
                                new MarketEntry(Material.POISONOUS_POTATO, 1), new MarketEntry(Material.CARROT, 5),
                                new MarketEntry(Material.APPLE, 10), new MarketEntry(Material.BEETROOT, 3),
                                new MarketEntry(Material.MELON_SLICE, 2), new MarketEntry(Material.SWEET_BERRIES, 5),
                                new MarketEntry(Material.GLOW_BERRIES, 15), new MarketEntry(Material.CHORUS_FRUIT, 15),
                                new MarketEntry(Material.SPIDER_EYE, 15), new MarketEntry(Material.ROTTEN_FLESH, 1),
                                // Cooked
                                new MarketEntry(Material.COOKED_CHICKEN, 12), new MarketEntry(Material.COOKED_COD, 10),
                                new MarketEntry(Material.COOKED_MUTTON, 14),
                                new MarketEntry(Material.COOKED_PORKCHOP, 16),
                                new MarketEntry(Material.COOKED_RABBIT, 14),
                                new MarketEntry(Material.COOKED_SALMON, 12),
                                new MarketEntry(Material.COOKED_BEEF, 16), new MarketEntry(Material.BAKED_POTATO, 8),
                                new MarketEntry(Material.BREAD, 10), new MarketEntry(Material.COOKIE, 5),
                                new MarketEntry(Material.CAKE, 100), new MarketEntry(Material.PUMPKIN_PIE, 25),
                                new MarketEntry(Material.MUSHROOM_STEW, 15),
                                new MarketEntry(Material.BEETROOT_SOUP, 12),
                                new MarketEntry(Material.RABBIT_STEW, 30),
                                new MarketEntry(Material.SUSPICIOUS_STEW, 40),
                                new MarketEntry(Material.DRIED_KELP, 2),
                                // Magical
                                new MarketEntry(Material.GOLDEN_APPLE, 1500),
                                new MarketEntry(Material.ENCHANTED_GOLDEN_APPLE, 25000),
                                new MarketEntry(Material.GOLDEN_CARROT, 200),
                                new MarketEntry(Material.GLISTERING_MELON_SLICE, 150),
                                new MarketEntry(Material.HONEY_BOTTLE, 30),
                                // Farming
                                new MarketEntry(Material.WHEAT_SEEDS, 1), new MarketEntry(Material.PUMPKIN_SEEDS, 2),
                                new MarketEntry(Material.MELON_SEEDS, 2), new MarketEntry(Material.BEETROOT_SEEDS, 1),
                                new MarketEntry(Material.TORCHFLOWER_SEEDS, 20),
                                new MarketEntry(Material.PITCHER_POD, 15),
                                new MarketEntry(Material.COCOA_BEANS, 8), new MarketEntry(Material.WHEAT, 5),
                                new MarketEntry(Material.SUGAR_CANE, 5), new MarketEntry(Material.SUGAR, 5),
                                new MarketEntry(Material.BONE_MEAL, 2), new MarketEntry(Material.EGG, 5),
                                new MarketEntry(Material.KELP, 2));

                // 💎 Ores, Minerals & Valuables
                add(Category.MINERALS_ORES,
                                new MarketEntry(Material.COAL, 50), new MarketEntry(Material.CHARCOAL, 40),
                                new MarketEntry(Material.DIAMOND, 5000),
                                new MarketEntry(Material.EMERALD, 1000), new MarketEntry(Material.LAPIS_LAZULI, 50),
                                new MarketEntry(Material.QUARTZ, 100), new MarketEntry(Material.REDSTONE, 50),
                                new MarketEntry(Material.AMETHYST_SHARD, 100),
                                new MarketEntry(Material.ECHO_SHARD, 2500),
                                // Raw
                                new MarketEntry(Material.RAW_COPPER, 100), new MarketEntry(Material.RAW_IRON, 200),
                                new MarketEntry(Material.RAW_GOLD, 500), new MarketEntry(Material.IRON_NUGGET, 20),
                                new MarketEntry(Material.GOLD_NUGGET, 50),
                                // Ores (Silk Touch)
                                new MarketEntry(Material.COAL_ORE, 75),
                                new MarketEntry(Material.DEEPSLATE_COAL_ORE, 85),
                                new MarketEntry(Material.COPPER_ORE, 120),
                                new MarketEntry(Material.DEEPSLATE_COPPER_ORE, 130),
                                new MarketEntry(Material.IRON_ORE, 220),
                                new MarketEntry(Material.DEEPSLATE_IRON_ORE, 230),
                                new MarketEntry(Material.GOLD_ORE, 550),
                                new MarketEntry(Material.DEEPSLATE_GOLD_ORE, 560),
                                new MarketEntry(Material.NETHER_GOLD_ORE, 100), new MarketEntry(Material.LAPIS_ORE, 70),
                                new MarketEntry(Material.DEEPSLATE_LAPIS_ORE, 80),
                                new MarketEntry(Material.REDSTONE_ORE, 70),
                                new MarketEntry(Material.DEEPSLATE_REDSTONE_ORE, 80),
                                new MarketEntry(Material.EMERALD_ORE, 1200),
                                new MarketEntry(Material.DEEPSLATE_EMERALD_ORE, 1300),
                                new MarketEntry(Material.DIAMOND_ORE, 6000),
                                new MarketEntry(Material.DEEPSLATE_DIAMOND_ORE, 6500),
                                new MarketEntry(Material.NETHER_QUARTZ_ORE, 120),
                                new MarketEntry(Material.ANCIENT_DEBRIS, 25000));

                // 🧟 Mob Drops
                add(Category.MOB_DROPS,
                                new MarketEntry(Material.BONE, 5), new MarketEntry(Material.FEATHER, 2),
                                new MarketEntry(Material.GUNPOWDER, 15), new MarketEntry(Material.INK_SAC, 5),
                                new MarketEntry(Material.GLOW_INK_SAC, 20), new MarketEntry(Material.LEATHER, 15),
                                new MarketEntry(Material.RABBIT_HIDE, 15), new MarketEntry(Material.SLIME_BALL, 20),
                                new MarketEntry(Material.STRING, 5), new MarketEntry(Material.RABBIT_FOOT, 150),
                                new MarketEntry(Material.ARMADILLO_SCUTE, 150),
                                new MarketEntry(Material.TURTLE_SCUTE, 250),
                                // Rare
                                new MarketEntry(Material.BLAZE_POWDER, 40), new MarketEntry(Material.BLAZE_ROD, 120),
                                new MarketEntry(Material.BREEZE_ROD, 150),
                                new MarketEntry(Material.DRAGON_BREATH, 500),
                                new MarketEntry(Material.ENDER_PEARL, 150),
                                new MarketEntry(Material.ENDER_EYE, 300),
                                new MarketEntry(Material.FERMENTED_SPIDER_EYE, 25),
                                new MarketEntry(Material.GHAST_TEAR, 300), new MarketEntry(Material.HEAVY_CORE, 20000),
                                new MarketEntry(Material.HONEYCOMB, 20), new MarketEntry(Material.MAGMA_CREAM, 40),
                                new MarketEntry(Material.NAUTILUS_SHELL, 300),
                                new MarketEntry(Material.NETHER_STAR, 75000),
                                new MarketEntry(Material.PHANTOM_MEMBRANE, 150),
                                new MarketEntry(Material.SHULKER_SHELL, 1000),
                                new MarketEntry(Material.TOTEM_OF_UNDYING, 25000),
                                new MarketEntry(Material.TRIAL_KEY, 1500),
                                new MarketEntry(Material.OMINOUS_TRIAL_KEY, 3000),
                                // Enchanting
                                new MarketEntry(Material.ENCHANTING_TABLE, 2500), new MarketEntry(Material.BOOK, 30),
                                new MarketEntry(Material.WRITABLE_BOOK, 50),
                                new MarketEntry(Material.WRITTEN_BOOK, 100),
                                new MarketEntry(Material.ENCHANTED_BOOK, 800),
                                new MarketEntry(Material.EXPERIENCE_BOTTLE, 150),
                                new MarketEntry(Material.BREWING_STAND, 250), new MarketEntry(Material.CAULDRON, 350),
                                new MarketEntry(Material.GLASS_BOTTLE, 2), new MarketEntry(Material.POTION, 100),
                                new MarketEntry(Material.SPLASH_POTION, 150),
                                new MarketEntry(Material.LINGERING_POTION, 500),
                                new MarketEntry(Material.OMINOUS_BOTTLE, 2000),
                                // Bucket
                                new MarketEntry(Material.BUCKET, 50), new MarketEntry(Material.WATER_BUCKET, 60),
                                new MarketEntry(Material.LAVA_BUCKET, 60),
                                new MarketEntry(Material.POWDER_SNOW_BUCKET, 110),
                                new MarketEntry(Material.MILK_BUCKET, 55),
                                new MarketEntry(Material.AXOLOTL_BUCKET, 500),
                                new MarketEntry(Material.COD_BUCKET, 60), new MarketEntry(Material.SALMON_BUCKET, 60),
                                new MarketEntry(Material.PUFFERFISH_BUCKET, 100),
                                new MarketEntry(Material.TADPOLE_BUCKET, 150),
                                new MarketEntry(Material.TROPICAL_FISH_BUCKET, 80),
                                new MarketEntry(Material.TURTLE_EGG, 500), new MarketEntry(Material.SNIFFER_EGG, 5000));

                // 🌿 Nature
                add(Category.NATURE,
                                new MarketEntry(Material.ACACIA_LEAVES, 1), new MarketEntry(Material.BIRCH_LEAVES, 1),
                                new MarketEntry(Material.CHERRY_LEAVES, 1),
                                new MarketEntry(Material.DARK_OAK_LEAVES, 1),
                                new MarketEntry(Material.JUNGLE_LEAVES, 1),
                                new MarketEntry(Material.MANGROVE_LEAVES, 1),
                                new MarketEntry(Material.OAK_LEAVES, 1), new MarketEntry(Material.SPRUCE_LEAVES, 1),
                                new MarketEntry(Material.AZALEA_LEAVES, 2),
                                new MarketEntry(Material.FLOWERING_AZALEA_LEAVES, 5),
                                // Saplings
                                new MarketEntry(Material.ACACIA_SAPLING, 15),
                                new MarketEntry(Material.BIRCH_SAPLING, 15),
                                new MarketEntry(Material.CHERRY_SAPLING, 15),
                                new MarketEntry(Material.DARK_OAK_SAPLING, 15),
                                new MarketEntry(Material.JUNGLE_SAPLING, 15), new MarketEntry(Material.OAK_SAPLING, 15),
                                new MarketEntry(Material.SPRUCE_SAPLING, 15), new MarketEntry(Material.AZALEA, 15),
                                new MarketEntry(Material.FLOWERING_AZALEA, 20),
                                new MarketEntry(Material.MANGROVE_PROPAGULE, 15),
                                new MarketEntry(Material.CRIMSON_FUNGUS, 10),
                                new MarketEntry(Material.WARPED_FUNGUS, 10),
                                // Plants
                                new MarketEntry(Material.BAMBOO, 2), new MarketEntry(Material.CACTUS, 5),
                                new MarketEntry(Material.DEAD_BUSH, 1), new MarketEntry(Material.FERN, 5),
                                new MarketEntry(Material.LARGE_FERN, 10),
                                // Dirt & Grass (Sell for 10%)
                                new MarketEntry(Material.DIRT, 0.5, 0.05),
                                new MarketEntry(Material.COARSE_DIRT, 0.5, 0.05),
                                new MarketEntry(Material.ROOTED_DIRT, 1, 0.1),
                                new MarketEntry(Material.GRASS_BLOCK, 2, 0.2),
                                new MarketEntry(Material.PODZOL, 2, 0.2), new MarketEntry(Material.MYCELIUM, 5, 0.5),
                                new MarketEntry(Material.DIRT_PATH, 1, 0.1),

                                new MarketEntry(Material.HANGING_ROOTS, 5), new MarketEntry(Material.MOSS_BLOCK, 10),
                                new MarketEntry(Material.MOSS_CARPET, 5), new MarketEntry(Material.PITCHER_PLANT, 20),
                                new MarketEntry(Material.SHORT_GRASS, 1),
                                new MarketEntry(Material.TALL_GRASS, 2), new MarketEntry(Material.SPORE_BLOSSOM, 500),
                                new MarketEntry(Material.VINE, 5),
                                // Flowers
                                new MarketEntry(Material.ALLIUM, 10), new MarketEntry(Material.AZURE_BLUET, 10),
                                new MarketEntry(Material.BLUE_ORCHID, 10), new MarketEntry(Material.CORNFLOWER, 10),
                                new MarketEntry(Material.DANDELION, 5), new MarketEntry(Material.LILAC, 10),
                                new MarketEntry(Material.LILY_OF_THE_VALLEY, 15),
                                new MarketEntry(Material.ORANGE_TULIP, 10),
                                new MarketEntry(Material.OXEYE_DAISY, 10), new MarketEntry(Material.PEONY, 10),
                                new MarketEntry(Material.PINK_PETALS, 5), new MarketEntry(Material.PINK_TULIP, 10),
                                new MarketEntry(Material.POPPY, 5), new MarketEntry(Material.RED_TULIP, 10),
                                new MarketEntry(Material.ROSE_BUSH, 15), new MarketEntry(Material.SUNFLOWER, 10),
                                new MarketEntry(Material.TORCHFLOWER, 50), new MarketEntry(Material.WHITE_TULIP, 10),
                                new MarketEntry(Material.WITHER_ROSE, 1500),
                                // Nether
                                new MarketEntry(Material.CRIMSON_ROOTS, 2), new MarketEntry(Material.WARPED_ROOTS, 2),
                                new MarketEntry(Material.NETHER_SPROUTS, 5), new MarketEntry(Material.NETHER_WART, 10),
                                new MarketEntry(Material.NETHER_WART_BLOCK, 90),
                                new MarketEntry(Material.SHROOMLIGHT, 50),
                                new MarketEntry(Material.TWISTING_VINES, 10),
                                new MarketEntry(Material.WEEPING_VINES, 10),
                                // Ocean
                                new MarketEntry(Material.BRAIN_CORAL, 50), new MarketEntry(Material.BUBBLE_CORAL, 50),
                                new MarketEntry(Material.FIRE_CORAL, 50), new MarketEntry(Material.HORN_CORAL, 50),
                                new MarketEntry(Material.TUBE_CORAL, 50),
                                new MarketEntry(Material.BRAIN_CORAL_BLOCK, 200),
                                new MarketEntry(Material.BUBBLE_CORAL_BLOCK, 200),
                                new MarketEntry(Material.FIRE_CORAL_BLOCK, 200),
                                new MarketEntry(Material.HORN_CORAL_BLOCK, 200),
                                new MarketEntry(Material.TUBE_CORAL_BLOCK, 200),
                                new MarketEntry(Material.LILY_PAD, 5), new MarketEntry(Material.SEA_PICKLE, 15),
                                new MarketEntry(Material.SEAGRASS, 2), new MarketEntry(Material.SPONGE, 1500),
                                new MarketEntry(Material.WET_SPONGE, 1500));

                // ⚙️ Redstone
                add(Category.REDSTONE,
                                new MarketEntry(Material.CRAFTING_TABLE, 10), new MarketEntry(Material.CRAFTER, 150),
                                new MarketEntry(Material.FURNACE, 15), new MarketEntry(Material.BLAST_FURNACE, 100),
                                new MarketEntry(Material.SMOKER, 80), new MarketEntry(Material.ANVIL, 800),
                                new MarketEntry(Material.CHIPPED_ANVIL, 400),
                                new MarketEntry(Material.DAMAGED_ANVIL, 100),
                                new MarketEntry(Material.SMITHING_TABLE, 150),
                                new MarketEntry(Material.STONECUTTER, 100),
                                new MarketEntry(Material.GRINDSTONE, 80),
                                new MarketEntry(Material.CARTOGRAPHY_TABLE, 100),
                                new MarketEntry(Material.FLETCHING_TABLE, 50), new MarketEntry(Material.LOOM, 30),
                                new MarketEntry(Material.CHEST, 20), new MarketEntry(Material.TRAPPED_CHEST, 50),
                                new MarketEntry(Material.BARREL, 30), new MarketEntry(Material.ENDER_CHEST, 2000),
                                new MarketEntry(Material.HOPPER, 250),
                                // Vehicles
                                new MarketEntry(Material.MINECART, 250), new MarketEntry(Material.CHEST_MINECART, 270),
                                new MarketEntry(Material.FURNACE_MINECART, 265),
                                new MarketEntry(Material.HOPPER_MINECART, 500),
                                new MarketEntry(Material.TNT_MINECART, 450), new MarketEntry(Material.BAMBOO_RAFT, 25),
                                new MarketEntry(Material.BAMBOO_CHEST_RAFT, 35),
                                // Boats (Generic Oak for now, or all)
                                new MarketEntry(Material.OAK_BOAT, 25), new MarketEntry(Material.OAK_CHEST_BOAT, 35),
                                // Components
                                new MarketEntry(Material.REDSTONE_TORCH, 15), new MarketEntry(Material.REPEATER, 80),
                                new MarketEntry(Material.COMPARATOR, 150), new MarketEntry(Material.REDSTONE_LAMP, 80),
                                new MarketEntry(Material.PISTON, 100), new MarketEntry(Material.STICKY_PISTON, 120),
                                new MarketEntry(Material.SLIME_BLOCK, 180), new MarketEntry(Material.HONEY_BLOCK, 100),
                                new MarketEntry(Material.DISPENSER, 150), new MarketEntry(Material.DROPPER, 80),
                                new MarketEntry(Material.OBSERVER, 200), new MarketEntry(Material.TARGET, 50),
                                new MarketEntry(Material.LEVER, 5), new MarketEntry(Material.TRIPWIRE_HOOK, 25),
                                new MarketEntry(Material.LIGHTNING_ROD, 45),
                                new MarketEntry(Material.DAYLIGHT_DETECTOR, 150),
                                new MarketEntry(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, 150),
                                new MarketEntry(Material.LIGHT_WEIGHTED_PRESSURE_PLATE, 150),
                                // Sculk
                                new MarketEntry(Material.SCULK, 50), new MarketEntry(Material.SCULK_SENSOR, 200),
                                new MarketEntry(Material.CALIBRATED_SCULK_SENSOR, 2000),
                                new MarketEntry(Material.SCULK_SHRIEKER, 1000),
                                new MarketEntry(Material.SCULK_CATALYST, 500), new MarketEntry(Material.SCULK_VEIN, 20),
                                // Rails
                                new MarketEntry(Material.RAIL, 10), new MarketEntry(Material.POWERED_RAIL, 120),
                                new MarketEntry(Material.DETECTOR_RAIL, 50),
                                new MarketEntry(Material.ACTIVATOR_RAIL, 40),
                                // Utilities
                                new MarketEntry(Material.TORCH, 5), new MarketEntry(Material.SOUL_TORCH, 15),
                                new MarketEntry(Material.LANTERN, 40), new MarketEntry(Material.SOUL_LANTERN, 60),
                                new MarketEntry(Material.CAMPFIRE, 40), new MarketEntry(Material.SOUL_CAMPFIRE, 60),
                                new MarketEntry(Material.BELL, 600), new MarketEntry(Material.BOOKSHELF, 120),
                                new MarketEntry(Material.CHISELED_BOOKSHELF, 150),
                                new MarketEntry(Material.LECTERN, 80),
                                new MarketEntry(Material.COMPOSTER, 30), new MarketEntry(Material.NOTE_BLOCK, 40),
                                new MarketEntry(Material.JUKEBOX, 1000), new MarketEntry(Material.SCAFFOLDING, 5),
                                new MarketEntry(Material.LADDER, 5), new MarketEntry(Material.COBWEB, 50),
                                new MarketEntry(Material.TNT, 200));

                add(Category.WOOD,
                                // --- OAK ---
                                new MarketEntry(Material.OAK_LOG, 5), new MarketEntry(Material.OAK_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_OAK_LOG, 6),
                                new MarketEntry(Material.STRIPPED_OAK_WOOD, 6),
                                new MarketEntry(Material.OAK_PLANKS, 2), new MarketEntry(Material.OAK_STAIRS, 3),
                                new MarketEntry(Material.OAK_SLAB, 1.5), new MarketEntry(Material.OAK_FENCE, 5),
                                new MarketEntry(Material.OAK_FENCE_GATE, 8), new MarketEntry(Material.OAK_DOOR, 10),
                                new MarketEntry(Material.OAK_TRAPDOOR, 5),
                                new MarketEntry(Material.OAK_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.OAK_BUTTON, 2), new MarketEntry(Material.OAK_SIGN, 10),
                                new MarketEntry(Material.OAK_HANGING_SIGN, 15), new MarketEntry(Material.OAK_BOAT, 25),
                                new MarketEntry(Material.OAK_CHEST_BOAT, 35),

                                // --- PALE OAK (Currently not in 1.21.1 API) ---
                                /*
                                 * new MarketEntry(Material.PALE_OAK_LOG, 5), new
                                 * MarketEntry(Material.PALE_OAK_WOOD, 5),
                                 * new MarketEntry(Material.STRIPPED_PALE_OAK_LOG, 6),
                                 * new MarketEntry(Material.STRIPPED_PALE_OAK_WOOD, 6),
                                 * new MarketEntry(Material.PALE_OAK_PLANKS, 2),
                                 * new MarketEntry(Material.PALE_OAK_STAIRS, 3),
                                 * new MarketEntry(Material.PALE_OAK_SLAB, 1.5),
                                 * new MarketEntry(Material.PALE_OAK_FENCE, 5),
                                 * new MarketEntry(Material.PALE_OAK_FENCE_GATE, 8),
                                 * new MarketEntry(Material.PALE_OAK_DOOR, 10),
                                 * new MarketEntry(Material.PALE_OAK_TRAPDOOR, 5),
                                 * new MarketEntry(Material.PALE_OAK_PRESSURE_PLATE, 5),
                                 * new MarketEntry(Material.PALE_OAK_BUTTON, 2),
                                 * new MarketEntry(Material.PALE_OAK_SIGN, 10),
                                 * new MarketEntry(Material.PALE_OAK_HANGING_SIGN, 15),
                                 * new MarketEntry(Material.PALE_OAK_BOAT, 25),
                                 * new MarketEntry(Material.PALE_OAK_CHEST_BOAT, 35),
                                 */

                                // --- SPRUCE ---
                                new MarketEntry(Material.SPRUCE_LOG, 5), new MarketEntry(Material.SPRUCE_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_SPRUCE_LOG, 6),
                                new MarketEntry(Material.STRIPPED_SPRUCE_WOOD, 6),
                                new MarketEntry(Material.SPRUCE_PLANKS, 2), new MarketEntry(Material.SPRUCE_STAIRS, 3),
                                new MarketEntry(Material.SPRUCE_SLAB, 1.5), new MarketEntry(Material.SPRUCE_FENCE, 5),
                                new MarketEntry(Material.SPRUCE_FENCE_GATE, 8),
                                new MarketEntry(Material.SPRUCE_DOOR, 10),
                                new MarketEntry(Material.SPRUCE_TRAPDOOR, 5),
                                new MarketEntry(Material.SPRUCE_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.SPRUCE_BUTTON, 2), new MarketEntry(Material.SPRUCE_SIGN, 10),
                                new MarketEntry(Material.SPRUCE_HANGING_SIGN, 15),
                                new MarketEntry(Material.SPRUCE_BOAT, 25),
                                new MarketEntry(Material.SPRUCE_CHEST_BOAT, 35),

                                // --- BIRCH ---
                                new MarketEntry(Material.BIRCH_LOG, 5), new MarketEntry(Material.BIRCH_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_BIRCH_LOG, 6),
                                new MarketEntry(Material.STRIPPED_BIRCH_WOOD, 6),
                                new MarketEntry(Material.BIRCH_PLANKS, 2), new MarketEntry(Material.BIRCH_STAIRS, 3),
                                new MarketEntry(Material.BIRCH_SLAB, 1.5), new MarketEntry(Material.BIRCH_FENCE, 5),
                                new MarketEntry(Material.BIRCH_FENCE_GATE, 8), new MarketEntry(Material.BIRCH_DOOR, 10),
                                new MarketEntry(Material.BIRCH_TRAPDOOR, 5),
                                new MarketEntry(Material.BIRCH_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.BIRCH_BUTTON, 2), new MarketEntry(Material.BIRCH_SIGN, 10),
                                new MarketEntry(Material.BIRCH_HANGING_SIGN, 15),
                                new MarketEntry(Material.BIRCH_BOAT, 25),
                                new MarketEntry(Material.BIRCH_CHEST_BOAT, 35),

                                // --- JUNGLE ---
                                new MarketEntry(Material.JUNGLE_LOG, 5), new MarketEntry(Material.JUNGLE_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_JUNGLE_LOG, 6),
                                new MarketEntry(Material.STRIPPED_JUNGLE_WOOD, 6),
                                new MarketEntry(Material.JUNGLE_PLANKS, 2), new MarketEntry(Material.JUNGLE_STAIRS, 3),
                                new MarketEntry(Material.JUNGLE_SLAB, 1.5), new MarketEntry(Material.JUNGLE_FENCE, 5),
                                new MarketEntry(Material.JUNGLE_FENCE_GATE, 8),
                                new MarketEntry(Material.JUNGLE_DOOR, 10),
                                new MarketEntry(Material.JUNGLE_TRAPDOOR, 5),
                                new MarketEntry(Material.JUNGLE_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.JUNGLE_BUTTON, 2), new MarketEntry(Material.JUNGLE_SIGN, 10),
                                new MarketEntry(Material.JUNGLE_HANGING_SIGN, 15),
                                new MarketEntry(Material.JUNGLE_BOAT, 25),
                                new MarketEntry(Material.JUNGLE_CHEST_BOAT, 35),

                                // --- ACACIA ---
                                new MarketEntry(Material.ACACIA_LOG, 5), new MarketEntry(Material.ACACIA_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_ACACIA_LOG, 6),
                                new MarketEntry(Material.STRIPPED_ACACIA_WOOD, 6),
                                new MarketEntry(Material.ACACIA_PLANKS, 2), new MarketEntry(Material.ACACIA_STAIRS, 3),
                                new MarketEntry(Material.ACACIA_SLAB, 1.5), new MarketEntry(Material.ACACIA_FENCE, 5),
                                new MarketEntry(Material.ACACIA_FENCE_GATE, 8),
                                new MarketEntry(Material.ACACIA_DOOR, 10),
                                new MarketEntry(Material.ACACIA_TRAPDOOR, 5),
                                new MarketEntry(Material.ACACIA_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.ACACIA_BUTTON, 2), new MarketEntry(Material.ACACIA_SIGN, 10),
                                new MarketEntry(Material.ACACIA_HANGING_SIGN, 15),
                                new MarketEntry(Material.ACACIA_BOAT, 25),
                                new MarketEntry(Material.ACACIA_CHEST_BOAT, 35),

                                // --- DARK OAK ---
                                new MarketEntry(Material.DARK_OAK_LOG, 5), new MarketEntry(Material.DARK_OAK_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_DARK_OAK_LOG, 6),
                                new MarketEntry(Material.STRIPPED_DARK_OAK_WOOD, 6),
                                new MarketEntry(Material.DARK_OAK_PLANKS, 2),
                                new MarketEntry(Material.DARK_OAK_STAIRS, 3),
                                new MarketEntry(Material.DARK_OAK_SLAB, 1.5),
                                new MarketEntry(Material.DARK_OAK_FENCE, 5),
                                new MarketEntry(Material.DARK_OAK_FENCE_GATE, 8),
                                new MarketEntry(Material.DARK_OAK_DOOR, 10),
                                new MarketEntry(Material.DARK_OAK_TRAPDOOR, 5),
                                new MarketEntry(Material.DARK_OAK_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.DARK_OAK_BUTTON, 2),
                                new MarketEntry(Material.DARK_OAK_SIGN, 10),
                                new MarketEntry(Material.DARK_OAK_HANGING_SIGN, 15),
                                new MarketEntry(Material.DARK_OAK_BOAT, 25),
                                new MarketEntry(Material.DARK_OAK_CHEST_BOAT, 35),

                                // --- MANGROVE ---
                                new MarketEntry(Material.MANGROVE_LOG, 5), new MarketEntry(Material.MANGROVE_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_MANGROVE_LOG, 6),
                                new MarketEntry(Material.STRIPPED_MANGROVE_WOOD, 6),
                                new MarketEntry(Material.MANGROVE_PLANKS, 2),
                                new MarketEntry(Material.MANGROVE_STAIRS, 3),
                                new MarketEntry(Material.MANGROVE_SLAB, 1.5),
                                new MarketEntry(Material.MANGROVE_FENCE, 5),
                                new MarketEntry(Material.MANGROVE_FENCE_GATE, 8),
                                new MarketEntry(Material.MANGROVE_DOOR, 10),
                                new MarketEntry(Material.MANGROVE_TRAPDOOR, 5),
                                new MarketEntry(Material.MANGROVE_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.MANGROVE_BUTTON, 2),
                                new MarketEntry(Material.MANGROVE_SIGN, 10),
                                new MarketEntry(Material.MANGROVE_HANGING_SIGN, 15),
                                new MarketEntry(Material.MANGROVE_BOAT, 25),
                                new MarketEntry(Material.MANGROVE_CHEST_BOAT, 35),

                                // --- CHERRY ---
                                new MarketEntry(Material.CHERRY_LOG, 5), new MarketEntry(Material.CHERRY_WOOD, 5),
                                new MarketEntry(Material.STRIPPED_CHERRY_LOG, 6),
                                new MarketEntry(Material.STRIPPED_CHERRY_WOOD, 6),
                                new MarketEntry(Material.CHERRY_PLANKS, 2), new MarketEntry(Material.CHERRY_STAIRS, 3),
                                new MarketEntry(Material.CHERRY_SLAB, 1.5), new MarketEntry(Material.CHERRY_FENCE, 5),
                                new MarketEntry(Material.CHERRY_FENCE_GATE, 8),
                                new MarketEntry(Material.CHERRY_DOOR, 10),
                                new MarketEntry(Material.CHERRY_TRAPDOOR, 5),
                                new MarketEntry(Material.CHERRY_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.CHERRY_BUTTON, 2), new MarketEntry(Material.CHERRY_SIGN, 10),
                                new MarketEntry(Material.CHERRY_HANGING_SIGN, 15),
                                new MarketEntry(Material.CHERRY_BOAT, 25),
                                new MarketEntry(Material.CHERRY_CHEST_BOAT, 35));

                // --- PALE OAK (Dynamic for 1.21.11 Winter Drop compat) ---
                String[] paleOakItems = {
                                "PALE_OAK_LOG:5", "PALE_OAK_WOOD:5", "STRIPPED_PALE_OAK_LOG:6",
                                "STRIPPED_PALE_OAK_WOOD:6",
                                "PALE_OAK_PLANKS:2", "PALE_OAK_STAIRS:3", "PALE_OAK_SLAB:1.5", "PALE_OAK_FENCE:5",
                                "PALE_OAK_FENCE_GATE:8", "PALE_OAK_DOOR:10", "PALE_OAK_TRAPDOOR:5",
                                "PALE_OAK_PRESSURE_PLATE:5", "PALE_OAK_BUTTON:2", "PALE_OAK_SIGN:10",
                                "PALE_OAK_HANGING_SIGN:15", "PALE_OAK_BOAT:25", "PALE_OAK_CHEST_BOAT:35",
                                "PALE_MOSS_BLOCK:10", "PALE_MOSS_CARPET:5", "PALE_HANGING_MOSS:15"
                };

                for (String paleItemStr : paleOakItems) {
                        String[] parts = paleItemStr.split(":");
                        Material mat = Material.matchMaterial(parts[0]);
                        if (mat != null) {
                                items.get(Category.WOOD).add(new MarketEntry(mat, Double.parseDouble(parts[1])));
                        }
                }

                add(Category.WOOD,
                                // --- CRIMSON (Nether +10%) ---
                                new MarketEntry(Material.CRIMSON_STEM, 5.5),
                                new MarketEntry(Material.CRIMSON_HYPHAE, 5.5),
                                new MarketEntry(Material.STRIPPED_CRIMSON_STEM, 6.6),
                                new MarketEntry(Material.STRIPPED_CRIMSON_HYPHAE, 6.6),
                                new MarketEntry(Material.CRIMSON_PLANKS, 2.2),
                                new MarketEntry(Material.CRIMSON_STAIRS, 3.3),
                                new MarketEntry(Material.CRIMSON_SLAB, 1.65),
                                new MarketEntry(Material.CRIMSON_FENCE, 5.5),
                                new MarketEntry(Material.CRIMSON_FENCE_GATE, 8.8),
                                new MarketEntry(Material.CRIMSON_DOOR, 11),
                                new MarketEntry(Material.CRIMSON_TRAPDOOR, 5.5),
                                new MarketEntry(Material.CRIMSON_PRESSURE_PLATE, 5.5),
                                new MarketEntry(Material.CRIMSON_BUTTON, 2.2),
                                new MarketEntry(Material.CRIMSON_SIGN, 11),
                                new MarketEntry(Material.CRIMSON_HANGING_SIGN, 16.5),
                                // No boat for Crimson

                                // --- WARPED (Nether +10%) ---
                                new MarketEntry(Material.WARPED_STEM, 5.5),
                                new MarketEntry(Material.WARPED_HYPHAE, 5.5),
                                new MarketEntry(Material.STRIPPED_WARPED_STEM, 6.6),
                                new MarketEntry(Material.STRIPPED_WARPED_HYPHAE, 6.6),
                                new MarketEntry(Material.WARPED_PLANKS, 2.2),
                                new MarketEntry(Material.WARPED_STAIRS, 3.3),
                                new MarketEntry(Material.WARPED_SLAB, 1.65),
                                new MarketEntry(Material.WARPED_FENCE, 5.5),
                                new MarketEntry(Material.WARPED_FENCE_GATE, 8.8),
                                new MarketEntry(Material.WARPED_DOOR, 11),
                                new MarketEntry(Material.WARPED_TRAPDOOR, 5.5),
                                new MarketEntry(Material.WARPED_PRESSURE_PLATE, 5.5),
                                new MarketEntry(Material.WARPED_BUTTON, 2.2), new MarketEntry(Material.WARPED_SIGN, 11),
                                new MarketEntry(Material.WARPED_HANGING_SIGN, 16.5),
                                // No boat for Warped

                                // --- BAMBOO (Complete set) ---
                                new MarketEntry(Material.BAMBOO_BLOCK, 10), new MarketEntry(Material.BAMBOO_PLANKS, 2),
                                new MarketEntry(Material.BAMBOO_MOSAIC, 4), new MarketEntry(Material.BAMBOO_STAIRS, 3),
                                new MarketEntry(Material.BAMBOO_SLAB, 1.5), new MarketEntry(Material.BAMBOO_FENCE, 5),
                                new MarketEntry(Material.BAMBOO_FENCE_GATE, 8),
                                new MarketEntry(Material.BAMBOO_DOOR, 10),
                                new MarketEntry(Material.BAMBOO_TRAPDOOR, 5),
                                new MarketEntry(Material.BAMBOO_PRESSURE_PLATE, 5),
                                new MarketEntry(Material.BAMBOO_BUTTON, 2), new MarketEntry(Material.BAMBOO_SIGN, 10),
                                new MarketEntry(Material.BAMBOO_HANGING_SIGN, 15),
                                new MarketEntry(Material.BAMBOO_RAFT, 25),
                                new MarketEntry(Material.BAMBOO_CHEST_RAFT, 35)); // 🟧 Copper
                add(Category.COPPER,
                                // Base
                                new MarketEntry(Material.COPPER_ORE, 120),
                                new MarketEntry(Material.DEEPSLATE_COPPER_ORE, 130),
                                new MarketEntry(Material.RAW_COPPER, 100),

                                // Normal
                                new MarketEntry(Material.CHISELED_COPPER, 20),
                                new MarketEntry(Material.COPPER_BULB, 25),
                                new MarketEntry(Material.COPPER_DOOR, 20),
                                new MarketEntry(Material.COPPER_GRATE, 15),
                                new MarketEntry(Material.COPPER_TRAPDOOR, 15),
                                new MarketEntry(Material.CUT_COPPER, 15),
                                new MarketEntry(Material.CUT_COPPER_SLAB, 7.5),
                                new MarketEntry(Material.CUT_COPPER_STAIRS, 15),
                                // Exposed
                                new MarketEntry(Material.EXPOSED_COPPER, 15),
                                new MarketEntry(Material.EXPOSED_CHISELED_COPPER, 20),
                                new MarketEntry(Material.EXPOSED_COPPER_BULB, 25),
                                new MarketEntry(Material.EXPOSED_COPPER_DOOR, 20),
                                new MarketEntry(Material.EXPOSED_COPPER_GRATE, 15),
                                new MarketEntry(Material.EXPOSED_COPPER_TRAPDOOR, 15),
                                new MarketEntry(Material.EXPOSED_CUT_COPPER, 15),
                                new MarketEntry(Material.EXPOSED_CUT_COPPER_SLAB, 7.5),
                                new MarketEntry(Material.EXPOSED_CUT_COPPER_STAIRS, 15),
                                // Weathered
                                new MarketEntry(Material.WEATHERED_COPPER, 15),
                                new MarketEntry(Material.WEATHERED_CHISELED_COPPER, 20),
                                new MarketEntry(Material.WEATHERED_COPPER_BULB, 25),
                                new MarketEntry(Material.WEATHERED_COPPER_DOOR, 20),
                                new MarketEntry(Material.WEATHERED_COPPER_GRATE, 15),
                                new MarketEntry(Material.WEATHERED_COPPER_TRAPDOOR, 15),
                                new MarketEntry(Material.WEATHERED_CUT_COPPER, 15),
                                new MarketEntry(Material.WEATHERED_CUT_COPPER_SLAB, 7.5),
                                new MarketEntry(Material.WEATHERED_CUT_COPPER_STAIRS, 15),
                                // Oxidized
                                new MarketEntry(Material.OXIDIZED_COPPER, 15),
                                new MarketEntry(Material.OXIDIZED_CHISELED_COPPER, 20),
                                new MarketEntry(Material.OXIDIZED_COPPER_BULB, 25),
                                new MarketEntry(Material.OXIDIZED_COPPER_DOOR, 20),
                                new MarketEntry(Material.OXIDIZED_COPPER_GRATE, 15),
                                new MarketEntry(Material.OXIDIZED_COPPER_TRAPDOOR, 15),
                                new MarketEntry(Material.OXIDIZED_CUT_COPPER, 15),
                                new MarketEntry(Material.OXIDIZED_CUT_COPPER_SLAB, 7.5),
                                new MarketEntry(Material.OXIDIZED_CUT_COPPER_STAIRS, 15),
                                // Waxed Normal
                                new MarketEntry(Material.WAXED_CHISELED_COPPER, 25),
                                new MarketEntry(Material.WAXED_COPPER_BULB, 30),
                                new MarketEntry(Material.WAXED_COPPER_DOOR, 25),
                                new MarketEntry(Material.WAXED_COPPER_GRATE, 20),
                                new MarketEntry(Material.WAXED_COPPER_TRAPDOOR, 20),
                                new MarketEntry(Material.WAXED_CUT_COPPER, 20),
                                new MarketEntry(Material.WAXED_CUT_COPPER_SLAB, 10),
                                new MarketEntry(Material.WAXED_CUT_COPPER_STAIRS, 20),
                                // Waxed Exposed
                                new MarketEntry(Material.WAXED_EXPOSED_COPPER, 20),
                                new MarketEntry(Material.WAXED_EXPOSED_CHISELED_COPPER, 25),
                                new MarketEntry(Material.WAXED_EXPOSED_COPPER_BULB, 30),
                                new MarketEntry(Material.WAXED_EXPOSED_COPPER_DOOR, 25),
                                new MarketEntry(Material.WAXED_EXPOSED_COPPER_GRATE, 20),
                                new MarketEntry(Material.WAXED_EXPOSED_COPPER_TRAPDOOR, 20),
                                new MarketEntry(Material.WAXED_EXPOSED_CUT_COPPER, 20),
                                new MarketEntry(Material.WAXED_EXPOSED_CUT_COPPER_SLAB, 10),
                                new MarketEntry(Material.WAXED_EXPOSED_CUT_COPPER_STAIRS, 20),
                                // Waxed Weathered
                                new MarketEntry(Material.WAXED_WEATHERED_COPPER, 20),
                                new MarketEntry(Material.WAXED_WEATHERED_CHISELED_COPPER, 25),
                                new MarketEntry(Material.WAXED_WEATHERED_COPPER_BULB, 30),
                                new MarketEntry(Material.WAXED_WEATHERED_COPPER_DOOR, 25),
                                new MarketEntry(Material.WAXED_WEATHERED_COPPER_GRATE, 20),
                                new MarketEntry(Material.WAXED_WEATHERED_COPPER_TRAPDOOR, 20),
                                new MarketEntry(Material.WAXED_WEATHERED_CUT_COPPER, 20),
                                new MarketEntry(Material.WAXED_WEATHERED_CUT_COPPER_SLAB, 10),
                                new MarketEntry(Material.WAXED_WEATHERED_CUT_COPPER_STAIRS, 20),
                                // Waxed Oxidized
                                new MarketEntry(Material.WAXED_OXIDIZED_COPPER, 20),
                                new MarketEntry(Material.WAXED_OXIDIZED_CHISELED_COPPER, 25),
                                new MarketEntry(Material.WAXED_OXIDIZED_COPPER_BULB, 30),
                                new MarketEntry(Material.WAXED_OXIDIZED_COPPER_DOOR, 25),
                                new MarketEntry(Material.WAXED_OXIDIZED_COPPER_GRATE, 20),
                                new MarketEntry(Material.WAXED_OXIDIZED_COPPER_TRAPDOOR, 20),
                                new MarketEntry(Material.WAXED_OXIDIZED_CUT_COPPER, 20),
                                new MarketEntry(Material.WAXED_OXIDIZED_CUT_COPPER_SLAB, 10),
                                new MarketEntry(Material.WAXED_OXIDIZED_CUT_COPPER_STAIRS, 20));

                // 🕸️ Spawners
                add(Category.SPAWNERS,
                                // 🏛️ The "Economy Breakers"
                                new MarketEntry(Material.SPAWNER, 750000, "Iron Golem Spawner"),
                                new MarketEntry(Material.SPAWNER, 600000, "Villager Spawner"),
                                new MarketEntry(Material.SPAWNER, 500000, "Evoker Spawner"),
                                new MarketEntry(Material.SPAWNER, 400000, "Wither Skeleton Spawner"),
                                new MarketEntry(Material.SPAWNER, 350000, "Elder Guardian Spawner"),
                                new MarketEntry(Material.SPAWNER, 250000, "Vindicator Spawner"),

                                // 💎 High Tier
                                new MarketEntry(Material.SPAWNER, 200000, "Creeper Spawner"),
                                new MarketEntry(Material.SPAWNER, 200000, "Shulker Spawner"),
                                new MarketEntry(Material.SPAWNER, 175000, "Allay Spawner"),
                                new MarketEntry(Material.SPAWNER, 150000, "Enderman Spawner"),
                                new MarketEntry(Material.SPAWNER, 125000, "Ghast Spawner"),
                                new MarketEntry(Material.SPAWNER, 100000, "Blaze Spawner"),
                                new MarketEntry(Material.SPAWNER, 90000, "Witch Spawner"),
                                new MarketEntry(Material.SPAWNER, 85000, "Piglin Spawner"),
                                new MarketEntry(Material.SPAWNER, 80000, "Guardian Spawner"),
                                new MarketEntry(Material.SPAWNER, 75000, "Skeleton Spawner"),
                                new MarketEntry(Material.SPAWNER, 75000, "Zombified Piglin Spawner"),
                                new MarketEntry(Material.SPAWNER, 70000, "Breeze Spawner"),
                                new MarketEntry(Material.SPAWNER, 65000, "Ravager Spawner"),

                                // 🥇 Mid Tier
                                new MarketEntry(Material.SPAWNER, 60000, "Slime Spawner"),
                                new MarketEntry(Material.SPAWNER, 60000, "Mooshroom Spawner"),
                                new MarketEntry(Material.SPAWNER, 55000, "Pillager Spawner"),
                                new MarketEntry(Material.SPAWNER, 50000, "Cow Spawner"),
                                new MarketEntry(Material.SPAWNER, 50000, "Drowned Spawner"),
                                new MarketEntry(Material.SPAWNER, 45000, "Piglin Brute Spawner"),
                                new MarketEntry(Material.SPAWNER, 40000, "Nautilus Spawner"),
                                new MarketEntry(Material.SPAWNER, 40000, "Phantom Spawner"),
                                new MarketEntry(Material.SPAWNER, 40000, "Sheep Spawner"),
                                new MarketEntry(Material.SPAWNER, 35000, "Zombie Spawner"),
                                new MarketEntry(Material.SPAWNER, 35000, "Magma Cube Spawner"),
                                new MarketEntry(Material.SPAWNER, 35000, "Chicken Spawner"),
                                new MarketEntry(Material.SPAWNER, 35000, "Pig Spawner"),
                                new MarketEntry(Material.SPAWNER, 30000, "Sniffer Spawner"),
                                new MarketEntry(Material.SPAWNER, 30000, "Spider Spawner"),
                                new MarketEntry(Material.SPAWNER, 30000, "Hoglin Spawner"),
                                new MarketEntry(Material.SPAWNER, 25000, "Cave Spider Spawner"),
                                new MarketEntry(Material.SPAWNER, 25000, "Rabbit Spawner"),
                                new MarketEntry(Material.SPAWNER, 25000, "Pufferfish Spawner"),
                                new MarketEntry(Material.SPAWNER, 25000, "Zombie Villager Spawner"),
                                new MarketEntry(Material.SPAWNER, 25000, "Frog Spawner"),

                                // 🥈 Low Tier
                                new MarketEntry(Material.SPAWNER, 20000, "Salmon Spawner"),
                                new MarketEntry(Material.SPAWNER, 20000, "Cod Spawner"),
                                new MarketEntry(Material.SPAWNER, 20000, "Squid Spawner"),
                                new MarketEntry(Material.SPAWNER, 20000, "Glow Squid Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Tropical Fish Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Stray Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Bogged Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Armadillo Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Turtle Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Snow Golem Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Endermite Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Creaking Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Wandering Trader Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Bee Spawner"),
                                new MarketEntry(Material.SPAWNER, 15000, "Camel Spawner"),
                                new MarketEntry(Material.SPAWNER, 12000, "Parched Spawner"),
                                new MarketEntry(Material.SPAWNER, 12000, "Tadpole Spawner"),
                                new MarketEntry(Material.SPAWNER, 10000, "Husk Spawner"),
                                new MarketEntry(Material.SPAWNER, 10000, "Camel Husk Spawner"),
                                new MarketEntry(Material.SPAWNER, 10000, "Horse Spawner"),
                                new MarketEntry(Material.SPAWNER, 10000, "Donkey Spawner"),
                                new MarketEntry(Material.SPAWNER, 10000, "Mule Spawner"),
                                new MarketEntry(Material.SPAWNER, 10000, "Zombie Horse Spawner"),
                                new MarketEntry(Material.SPAWNER, 9000, "Skeleton Horse Spawner"),
                                new MarketEntry(Material.SPAWNER, 7000, "Zoglin Spawner"),
                                new MarketEntry(Material.SPAWNER, 5000, "Silverfish Spawner"),
                                new MarketEntry(Material.SPAWNER, 5000, "Wolf Spawner"),
                                new MarketEntry(Material.SPAWNER, 5000, "Cat Spawner"),

                                // 🥉 Bargain Bin
                                new MarketEntry(Material.SPAWNER, 4000, "Parrot Spawner"),
                                new MarketEntry(Material.SPAWNER, 4000, "Axolotl Spawner"),
                                new MarketEntry(Material.SPAWNER, 4000, "Fox Spawner"),
                                new MarketEntry(Material.SPAWNER, 4000, "Ocelot Spawner"),
                                new MarketEntry(Material.SPAWNER, 3000, "Polar Bear Spawner"),
                                new MarketEntry(Material.SPAWNER, 3000, "Panda Spawner"),
                                new MarketEntry(Material.SPAWNER, 3000, "Llama Spawner"),
                                new MarketEntry(Material.SPAWNER, 3000, "Trader Llama Spawner"),
                                new MarketEntry(Material.SPAWNER, 2000, "Dolphin Spawner"),
                                new MarketEntry(Material.SPAWNER, 2000, "Goat Spawner"),
                                new MarketEntry(Material.SPAWNER, 2000, "Strider Spawner"),
                                new MarketEntry(Material.SPAWNER, 1000, "Zombie Nautilus Spawner"));

                // 🎨 Colors (Sample items for brevity, would be huge list)
                add(Category.COLORS,
                                new MarketEntry(Material.WHITE_WOOL, 10), new MarketEntry(Material.ORANGE_WOOL, 10),
                                new MarketEntry(Material.MAGENTA_WOOL, 10),
                                new MarketEntry(Material.LIGHT_BLUE_WOOL, 10),
                                new MarketEntry(Material.YELLOW_WOOL, 10), new MarketEntry(Material.LIME_WOOL, 10),
                                new MarketEntry(Material.PINK_WOOL, 10), new MarketEntry(Material.GRAY_WOOL, 10),
                                new MarketEntry(Material.LIGHT_GRAY_WOOL, 10), new MarketEntry(Material.CYAN_WOOL, 10),
                                new MarketEntry(Material.PURPLE_WOOL, 10), new MarketEntry(Material.BLUE_WOOL, 10),
                                new MarketEntry(Material.BROWN_WOOL, 10), new MarketEntry(Material.GREEN_WOOL, 10),
                                new MarketEntry(Material.RED_WOOL, 10), new MarketEntry(Material.BLACK_WOOL, 10),
                                // Candle
                                new MarketEntry(Material.CANDLE, 10), new MarketEntry(Material.WHITE_CANDLE, 10),
                                // Dye
                                new MarketEntry(Material.WHITE_DYE, 5), new MarketEntry(Material.RED_DYE, 5),
                                new MarketEntry(Material.BLUE_DYE, 5), new MarketEntry(Material.YELLOW_DYE, 5),
                                new MarketEntry(Material.GREEN_DYE, 5), new MarketEntry(Material.BLACK_DYE, 5),
                                // (Add other dyes if needed, listing basics for now)

                                // --- CONCRETE POWDER (Sand+Gravel+Dye) ~1.5 ---
                                new MarketEntry(Material.WHITE_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.ORANGE_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.MAGENTA_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.LIGHT_BLUE_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.YELLOW_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.LIME_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.PINK_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.GRAY_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.LIGHT_GRAY_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.CYAN_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.PURPLE_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.BLUE_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.BROWN_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.GREEN_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.RED_CONCRETE_POWDER, 1.5),
                                new MarketEntry(Material.BLACK_CONCRETE_POWDER, 1.5),

                                // --- CONCRETE (Solidified) ~2.0 ---
                                new MarketEntry(Material.WHITE_CONCRETE, 2.0),
                                new MarketEntry(Material.ORANGE_CONCRETE, 2.0),
                                new MarketEntry(Material.MAGENTA_CONCRETE, 2.0),
                                new MarketEntry(Material.LIGHT_BLUE_CONCRETE, 2.0),
                                new MarketEntry(Material.YELLOW_CONCRETE, 2.0),
                                new MarketEntry(Material.LIME_CONCRETE, 2.0),
                                new MarketEntry(Material.PINK_CONCRETE, 2.0),
                                new MarketEntry(Material.GRAY_CONCRETE, 2.0),
                                new MarketEntry(Material.LIGHT_GRAY_CONCRETE, 2.0),
                                new MarketEntry(Material.CYAN_CONCRETE, 2.0),
                                new MarketEntry(Material.PURPLE_CONCRETE, 2.0),
                                new MarketEntry(Material.BLUE_CONCRETE, 2.0),
                                new MarketEntry(Material.BROWN_CONCRETE, 2.0),
                                new MarketEntry(Material.GREEN_CONCRETE, 2.0),
                                new MarketEntry(Material.RED_CONCRETE, 2.0),
                                new MarketEntry(Material.BLACK_CONCRETE, 2.0),

                                // --- TERRACOTTA (Smelted Clay) ~12.0 ---
                                new MarketEntry(Material.TERRACOTTA, 12),
                                new MarketEntry(Material.WHITE_TERRACOTTA, 12),
                                new MarketEntry(Material.ORANGE_TERRACOTTA, 12),
                                new MarketEntry(Material.MAGENTA_TERRACOTTA, 12),
                                new MarketEntry(Material.LIGHT_BLUE_TERRACOTTA, 12),
                                new MarketEntry(Material.YELLOW_TERRACOTTA, 12),
                                new MarketEntry(Material.LIME_TERRACOTTA, 12),
                                new MarketEntry(Material.PINK_TERRACOTTA, 12),
                                new MarketEntry(Material.GRAY_TERRACOTTA, 12),
                                new MarketEntry(Material.LIGHT_GRAY_TERRACOTTA, 12),
                                new MarketEntry(Material.CYAN_TERRACOTTA, 12),
                                new MarketEntry(Material.PURPLE_TERRACOTTA, 12),
                                new MarketEntry(Material.BLUE_TERRACOTTA, 12),
                                new MarketEntry(Material.BROWN_TERRACOTTA, 12),
                                new MarketEntry(Material.GREEN_TERRACOTTA, 12),
                                new MarketEntry(Material.RED_TERRACOTTA, 12),
                                new MarketEntry(Material.BLACK_TERRACOTTA, 12),

                                // --- GLAZED TERRACOTTA (Resmelted) ~14.0 ---
                                new MarketEntry(Material.WHITE_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.ORANGE_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.MAGENTA_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.LIGHT_BLUE_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.YELLOW_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.LIME_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.PINK_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.GRAY_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.LIGHT_GRAY_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.CYAN_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.PURPLE_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.BLUE_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.BROWN_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.GREEN_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.RED_GLAZED_TERRACOTTA, 14),
                                new MarketEntry(Material.BLACK_GLAZED_TERRACOTTA, 14),

                                // --- STAINED GLASS (Glass+Dye) ~3.0 ---
                                new MarketEntry(Material.WHITE_STAINED_GLASS, 3),
                                new MarketEntry(Material.ORANGE_STAINED_GLASS, 3),
                                new MarketEntry(Material.MAGENTA_STAINED_GLASS, 3),
                                new MarketEntry(Material.LIGHT_BLUE_STAINED_GLASS, 3),
                                new MarketEntry(Material.YELLOW_STAINED_GLASS, 3),
                                new MarketEntry(Material.LIME_STAINED_GLASS, 3),
                                new MarketEntry(Material.PINK_STAINED_GLASS, 3),
                                new MarketEntry(Material.GRAY_STAINED_GLASS, 3),
                                new MarketEntry(Material.LIGHT_GRAY_STAINED_GLASS, 3),
                                new MarketEntry(Material.CYAN_STAINED_GLASS, 3),
                                new MarketEntry(Material.PURPLE_STAINED_GLASS, 3),
                                new MarketEntry(Material.BLUE_STAINED_GLASS, 3),
                                new MarketEntry(Material.BROWN_STAINED_GLASS, 3),
                                new MarketEntry(Material.GREEN_STAINED_GLASS, 3),
                                new MarketEntry(Material.RED_STAINED_GLASS, 3),
                                new MarketEntry(Material.BLACK_STAINED_GLASS, 3),

                                // --- STAINED GLASS PANES (Cheaper) ~1.0 ---
                                new MarketEntry(Material.WHITE_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.ORANGE_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.MAGENTA_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.LIGHT_BLUE_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.YELLOW_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.LIME_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.PINK_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.GRAY_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.LIGHT_GRAY_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.CYAN_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.PURPLE_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.BLUE_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.BROWN_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.GREEN_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.RED_STAINED_GLASS_PANE, 1.0),
                                new MarketEntry(Material.BLACK_STAINED_GLASS_PANE, 1.0),

                                // --- CARPET (Wool -> Carpet) ~6.0 ---
                                new MarketEntry(Material.WHITE_CARPET, 6), new MarketEntry(Material.ORANGE_CARPET, 6),
                                new MarketEntry(Material.MAGENTA_CARPET, 6),
                                new MarketEntry(Material.LIGHT_BLUE_CARPET, 6),
                                new MarketEntry(Material.YELLOW_CARPET, 6), new MarketEntry(Material.LIME_CARPET, 6),
                                new MarketEntry(Material.PINK_CARPET, 6), new MarketEntry(Material.GRAY_CARPET, 6),
                                new MarketEntry(Material.LIGHT_GRAY_CARPET, 6),
                                new MarketEntry(Material.CYAN_CARPET, 6),
                                new MarketEntry(Material.PURPLE_CARPET, 6), new MarketEntry(Material.BLUE_CARPET, 6),
                                new MarketEntry(Material.BROWN_CARPET, 6), new MarketEntry(Material.GREEN_CARPET, 6),
                                new MarketEntry(Material.RED_CARPET, 6), new MarketEntry(Material.BLACK_CARPET, 6));

                // 🧱 Building Blocks
                add(Category.BUILDING,
                                // --- STONES & VARIATIONS (Common ~1.5 - 3.0) ---
                                // Stone (Sell for 10%)
                                new MarketEntry(Material.STONE, 1.5, 0.15),
                                new MarketEntry(Material.STONE_STAIRS, 2.25, 0.225),
                                new MarketEntry(Material.STONE_SLAB, 0.75, 0.075),
                                new MarketEntry(Material.STONE_PRESSURE_PLATE, 3),
                                new MarketEntry(Material.STONE_BUTTON, 1.5),
                                // Cobblestone (Sell for 10%)
                                new MarketEntry(Material.COBBLESTONE, 0.5, 0.05),
                                new MarketEntry(Material.COBBLESTONE_STAIRS, 0.75, 0.075),
                                new MarketEntry(Material.COBBLESTONE_SLAB, 0.25, 0.025),
                                new MarketEntry(Material.COBBLESTONE_WALL, 0.5, 0.05),
                                // Mossy Cobble
                                new MarketEntry(Material.MOSSY_COBBLESTONE, 5),
                                new MarketEntry(Material.MOSSY_COBBLESTONE_STAIRS, 7.5),
                                new MarketEntry(Material.MOSSY_COBBLESTONE_SLAB, 2.5),
                                new MarketEntry(Material.MOSSY_COBBLESTONE_WALL, 5),
                                // Smooth Stone
                                new MarketEntry(Material.SMOOTH_STONE, 3, 0.3),
                                new MarketEntry(Material.SMOOTH_STONE_SLAB, 1.5, 0.15),
                                // Stone Bricks
                                new MarketEntry(Material.STONE_BRICKS, 3, 0.3),
                                new MarketEntry(Material.STONE_BRICK_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.STONE_BRICK_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.STONE_BRICK_WALL, 3, 0.3),
                                new MarketEntry(Material.CHISELED_STONE_BRICKS, 4, 0.4),
                                new MarketEntry(Material.CRACKED_STONE_BRICKS, 2, 0.2),
                                new MarketEntry(Material.MOSSY_STONE_BRICKS, 6),
                                new MarketEntry(Material.MOSSY_STONE_BRICK_STAIRS, 9),
                                new MarketEntry(Material.MOSSY_STONE_BRICK_SLAB, 3),
                                new MarketEntry(Material.MOSSY_STONE_BRICK_WALL, 6),

                                // Natural Stones (Granite, Diorite, Andesite) ~2.0 (Sell for 10%)
                                new MarketEntry(Material.GRANITE, 2, 0.2),
                                new MarketEntry(Material.GRANITE_STAIRS, 3, 0.3),
                                new MarketEntry(Material.GRANITE_SLAB, 1, 0.1),
                                new MarketEntry(Material.GRANITE_WALL, 2, 0.2),
                                new MarketEntry(Material.POLISHED_GRANITE, 3, 0.3),
                                new MarketEntry(Material.POLISHED_GRANITE_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.POLISHED_GRANITE_SLAB, 1.5, 0.15),

                                new MarketEntry(Material.DIORITE, 2, 0.2),
                                new MarketEntry(Material.DIORITE_STAIRS, 3, 0.3),
                                new MarketEntry(Material.DIORITE_SLAB, 1, 0.1),
                                new MarketEntry(Material.DIORITE_WALL, 2, 0.2),
                                new MarketEntry(Material.POLISHED_DIORITE, 3, 0.3),
                                new MarketEntry(Material.POLISHED_DIORITE_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.POLISHED_DIORITE_SLAB, 1.5, 0.15),

                                new MarketEntry(Material.ANDESITE, 2, 0.2),
                                new MarketEntry(Material.ANDESITE_STAIRS, 3, 0.3),
                                new MarketEntry(Material.ANDESITE_SLAB, 1, 0.1),
                                new MarketEntry(Material.ANDESITE_WALL, 2, 0.2),
                                new MarketEntry(Material.POLISHED_ANDESITE, 3, 0.3),
                                new MarketEntry(Material.POLISHED_ANDESITE_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.POLISHED_ANDESITE_SLAB, 1.5, 0.15),

                                // Other Stones
                                new MarketEntry(Material.CALCITE, 10), new MarketEntry(Material.TUFF, 3, 0.3),
                                new MarketEntry(Material.TUFF_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.TUFF_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.TUFF_WALL, 3, 0.3),
                                new MarketEntry(Material.DRIPSTONE_BLOCK, 4, 0.4),
                                new MarketEntry(Material.POINTED_DRIPSTONE, 2, 0.2),

                                // Basalt
                                new MarketEntry(Material.BASALT, 2, 0.2),
                                new MarketEntry(Material.SMOOTH_BASALT, 3, 0.3),
                                new MarketEntry(Material.POLISHED_BASALT, 3, 0.3),

                                // --- DEEPSLATE & VARIANTS ---
                                new MarketEntry(Material.DEEPSLATE, 1, 0.1),
                                new MarketEntry(Material.COBBLED_DEEPSLATE, 1, 0.1),
                                new MarketEntry(Material.COBBLED_DEEPSLATE_STAIRS, 1.5, 0.15),
                                new MarketEntry(Material.COBBLED_DEEPSLATE_SLAB, 0.5, 0.05),
                                new MarketEntry(Material.COBBLED_DEEPSLATE_WALL, 1, 0.1),

                                new MarketEntry(Material.POLISHED_DEEPSLATE, 2, 0.2),
                                new MarketEntry(Material.POLISHED_DEEPSLATE_STAIRS, 3, 0.3),
                                new MarketEntry(Material.POLISHED_DEEPSLATE_SLAB, 1, 0.1),
                                new MarketEntry(Material.POLISHED_DEEPSLATE_WALL, 2, 0.2),

                                new MarketEntry(Material.DEEPSLATE_BRICKS, 3, 0.3),
                                new MarketEntry(Material.DEEPSLATE_BRICK_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.DEEPSLATE_BRICK_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.DEEPSLATE_BRICK_WALL, 3, 0.3),
                                new MarketEntry(Material.CRACKED_DEEPSLATE_BRICKS, 2, 0.2),

                                new MarketEntry(Material.DEEPSLATE_TILES, 3, 0.3),
                                new MarketEntry(Material.DEEPSLATE_TILE_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.DEEPSLATE_TILE_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.DEEPSLATE_TILE_WALL, 3, 0.3),
                                new MarketEntry(Material.CRACKED_DEEPSLATE_TILES, 2, 0.2),

                                new MarketEntry(Material.CHISELED_DEEPSLATE, 4, 0.4),
                                new MarketEntry(Material.REINFORCED_DEEPSLATE, 1000),

                                // --- BLACKSTONE ---
                                new MarketEntry(Material.BLACKSTONE, 2, 0.2),
                                new MarketEntry(Material.BLACKSTONE_STAIRS, 3, 0.3),
                                new MarketEntry(Material.BLACKSTONE_SLAB, 1, 0.1),
                                new MarketEntry(Material.BLACKSTONE_WALL, 2, 0.2),

                                new MarketEntry(Material.POLISHED_BLACKSTONE, 3, 0.3),
                                new MarketEntry(Material.POLISHED_BLACKSTONE_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.POLISHED_BLACKSTONE_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.POLISHED_BLACKSTONE_WALL, 3, 0.3),
                                new MarketEntry(Material.CHISELED_POLISHED_BLACKSTONE, 4, 0.4),

                                new MarketEntry(Material.POLISHED_BLACKSTONE_BRICKS, 3, 0.3),
                                new MarketEntry(Material.POLISHED_BLACKSTONE_BRICK_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.POLISHED_BLACKSTONE_BRICK_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.POLISHED_BLACKSTONE_BRICK_WALL, 3, 0.3),
                                new MarketEntry(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS, 2, 0.2),

                                // --- BRICKS & MUD ---
                                new MarketEntry(Material.BRICKS, 12), new MarketEntry(Material.BRICK_STAIRS, 18),
                                new MarketEntry(Material.BRICK_SLAB, 6), new MarketEntry(Material.BRICK_WALL, 12),
                                new MarketEntry(Material.MUD, 2, 0.2), new MarketEntry(Material.PACKED_MUD, 4, 0.4),
                                new MarketEntry(Material.MUD_BRICKS, 6, 0.6),
                                new MarketEntry(Material.MUD_BRICK_STAIRS, 9, 0.9),
                                new MarketEntry(Material.MUD_BRICK_SLAB, 3, 0.3),
                                new MarketEntry(Material.MUD_BRICK_WALL, 6, 0.6),

                                // --- SANDSTONE ---
                                new MarketEntry(Material.SANDSTONE, 4, 0.4),
                                new MarketEntry(Material.SANDSTONE_STAIRS, 6, 0.6),
                                new MarketEntry(Material.SANDSTONE_SLAB, 2, 0.2),
                                new MarketEntry(Material.SANDSTONE_WALL, 4, 0.4),
                                new MarketEntry(Material.CUT_SANDSTONE, 5, 0.5),
                                new MarketEntry(Material.CUT_SANDSTONE_SLAB, 2.5, 0.25),
                                new MarketEntry(Material.SMOOTH_SANDSTONE, 5, 0.5),
                                new MarketEntry(Material.SMOOTH_SANDSTONE_STAIRS, 7.5, 0.75),
                                new MarketEntry(Material.SMOOTH_SANDSTONE_SLAB, 2.5, 0.25),
                                new MarketEntry(Material.CHISELED_SANDSTONE, 6, 0.6),

                                new MarketEntry(Material.RED_SANDSTONE, 4, 0.4),
                                new MarketEntry(Material.RED_SANDSTONE_STAIRS, 6, 0.6),
                                new MarketEntry(Material.RED_SANDSTONE_SLAB, 2, 0.2),
                                new MarketEntry(Material.RED_SANDSTONE_WALL, 4, 0.4),
                                new MarketEntry(Material.CUT_RED_SANDSTONE, 5, 0.5),
                                new MarketEntry(Material.CUT_RED_SANDSTONE_SLAB, 2.5, 0.25),
                                new MarketEntry(Material.SMOOTH_RED_SANDSTONE, 5, 0.5),
                                new MarketEntry(Material.SMOOTH_RED_SANDSTONE_STAIRS, 7.5, 0.75),
                                new MarketEntry(Material.SMOOTH_RED_SANDSTONE_SLAB, 2.5, 0.25),
                                new MarketEntry(Material.CHISELED_RED_SANDSTONE, 6, 0.6),

                                // --- PRISMARINE (High Value) ---
                                new MarketEntry(Material.PRISMARINE, 30),
                                new MarketEntry(Material.PRISMARINE_STAIRS, 45),
                                new MarketEntry(Material.PRISMARINE_SLAB, 15),
                                new MarketEntry(Material.PRISMARINE_WALL, 30),
                                new MarketEntry(Material.PRISMARINE_BRICKS, 40),
                                new MarketEntry(Material.PRISMARINE_BRICK_STAIRS, 60),
                                new MarketEntry(Material.PRISMARINE_BRICK_SLAB, 20),
                                new MarketEntry(Material.DARK_PRISMARINE, 50),
                                new MarketEntry(Material.DARK_PRISMARINE_STAIRS, 75),
                                new MarketEntry(Material.DARK_PRISMARINE_SLAB, 25),

                                // --- NETHER BRICK & END STONE ---
                                new MarketEntry(Material.NETHER_BRICKS, 3, 0.3),
                                new MarketEntry(Material.NETHER_BRICK_STAIRS, 4.5, 0.45),
                                new MarketEntry(Material.NETHER_BRICK_SLAB, 1.5, 0.15),
                                new MarketEntry(Material.NETHER_BRICK_WALL, 3, 0.3),
                                new MarketEntry(Material.NETHER_BRICK_FENCE, 3, 0.3),
                                new MarketEntry(Material.CHISELED_NETHER_BRICKS, 4, 0.4),
                                new MarketEntry(Material.CRACKED_NETHER_BRICKS, 3, 0.3),
                                new MarketEntry(Material.RED_NETHER_BRICKS, 6, 0.6),
                                new MarketEntry(Material.RED_NETHER_BRICK_STAIRS, 9, 0.9),
                                new MarketEntry(Material.RED_NETHER_BRICK_SLAB, 3, 0.3),
                                new MarketEntry(Material.RED_NETHER_BRICK_WALL, 6, 0.6),

                                new MarketEntry(Material.END_STONE, 5, 0.5),
                                new MarketEntry(Material.END_STONE_BRICKS, 10, 1.0),
                                new MarketEntry(Material.END_STONE_BRICK_STAIRS, 15, 1.5),
                                new MarketEntry(Material.END_STONE_BRICK_SLAB, 5, 0.5),
                                new MarketEntry(Material.END_STONE_BRICK_WALL, 10, 1.0),

                                // --- PURPUR ---
                                new MarketEntry(Material.PURPUR_BLOCK, 20), new MarketEntry(Material.PURPUR_STAIRS, 30),
                                new MarketEntry(Material.PURPUR_SLAB, 10), new MarketEntry(Material.PURPUR_PILLAR, 20),

                                // --- QUARTZ ---
                                new MarketEntry(Material.QUARTZ_BLOCK, 400),
                                new MarketEntry(Material.QUARTZ_STAIRS, 600),
                                new MarketEntry(Material.QUARTZ_SLAB, 200),
                                new MarketEntry(Material.CHISELED_QUARTZ_BLOCK, 450),
                                new MarketEntry(Material.QUARTZ_BRICKS, 400),
                                new MarketEntry(Material.QUARTZ_PILLAR, 400),
                                new MarketEntry(Material.SMOOTH_QUARTZ, 450),
                                new MarketEntry(Material.SMOOTH_QUARTZ_STAIRS, 675),
                                new MarketEntry(Material.SMOOTH_QUARTZ_SLAB, 225),

                                // --- GLASS ---
                                new MarketEntry(Material.GLASS, 2, 0.2),
                                new MarketEntry(Material.GLASS_PANE, 0.75, 0.075),
                                new MarketEntry(Material.TINTED_GLASS, 202),

                                // --- MISC DECORATIVE ---
                                new MarketEntry(Material.OBSIDIAN, 150),
                                new MarketEntry(Material.CRYING_OBSIDIAN, 300),
                                new MarketEntry(Material.AMETHYST_BLOCK, 400),
                                /* Material.CHAIN changed/removed */ new MarketEntry(
                                                Material.matchMaterial("CHAIN") != null
                                                                ? Material.matchMaterial("CHAIN")
                                                                : Material.IRON_NUGGET /* fallback */,
                                                20),
                                new MarketEntry(Material.IRON_BARS, 20),
                                new MarketEntry(Material.GRAVEL, 0.5, 0.05), new MarketEntry(Material.SAND, 1, 0.1),
                                new MarketEntry(Material.RED_SAND, 1.5, 0.15), new MarketEntry(Material.CLAY, 10),
                                new MarketEntry(Material.SNOW_BLOCK, 2, 0.2), new MarketEntry(Material.ICE, 5, 0.5),
                                new MarketEntry(Material.PACKED_ICE, 45, 4.5),
                                new MarketEntry(Material.BLUE_ICE, 405, 40.5),
                                new MarketEntry(Material.NETHERRACK, 0.2, 0.02),
                                new MarketEntry(Material.SOUL_SAND, 15),
                                new MarketEntry(Material.SOUL_SOIL, 15), new MarketEntry(Material.MAGMA_BLOCK, 30));

                // 🎭 Decoration
                add(Category.DECORATION,
                                new MarketEntry(Material.PAINTING, 30), new MarketEntry(Material.ITEM_FRAME, 15),
                                new MarketEntry(Material.GLOW_ITEM_FRAME, 40),
                                new MarketEntry(Material.ARMOR_STAND, 50),
                                new MarketEntry(Material.BEACON, 80000),
                                new MarketEntry(Material.CONDUIT, 25000),
                                new MarketEntry(Material.HEART_OF_THE_SEA, 15000),
                                new MarketEntry(Material.LODESTONE, 15000),
                                new MarketEntry(Material.RESPAWN_ANCHOR, 5000),
                                new MarketEntry(Material.END_CRYSTAL, 1500),
                                // Heads
                                new MarketEntry(Material.CREEPER_HEAD, 2500),

                                new MarketEntry(Material.PIGLIN_HEAD, 3000),
                                new MarketEntry(Material.SKELETON_SKULL, 1500),
                                new MarketEntry(Material.WITHER_SKELETON_SKULL, 2500),
                                new MarketEntry(Material.ZOMBIE_HEAD, 2500),
                                // Trims

                                // Pottery
                                new MarketEntry(Material.DECORATED_POT, 150), new MarketEntry(Material.FLOWER_POT, 15),
                                // Music Discs
                                new MarketEntry(Material.MUSIC_DISC_13, 5000),
                                new MarketEntry(Material.MUSIC_DISC_CAT, 5000),
                                new MarketEntry(Material.MUSIC_DISC_PIGSTEP, 10000),
                                new MarketEntry(Material.DISC_FRAGMENT_5, 1000));

                // 📖 Enchantment Books
                add(Category.ENCHANTMENTS,
                                // Protection
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Protection I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Protection II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Protection III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Protection IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Fire Protection I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Fire Protection II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Fire Protection III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Fire Protection IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Blast Protection I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Blast Protection II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Blast Protection III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Blast Protection IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Projectile Protection I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Projectile Protection II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Projectile Protection III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Projectile Protection IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Thorns I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Thorns II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Thorns III"),
                                // Weapon Enchants
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Sharpness I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Sharpness II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Sharpness III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Sharpness IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Sharpness V"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Smite I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Smite II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Smite III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Smite IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Smite V"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Bane of Arthropods I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Bane of Arthropods II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Bane of Arthropods III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Bane of Arthropods IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Bane of Arthropods V"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 75, "Knockback I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Knockback II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 75, "Fire Aspect I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Fire Aspect II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Looting I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Looting II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Looting III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 75, "Sweeping Edge I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Sweeping Edge II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 300, "Sweeping Edge III"),
                                // Bow Enchants
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Power I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Power II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Power III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Power IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Power V"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 75, "Punch I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Punch II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Flame"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Infinity"),
                                // Crossbow
                                new MarketEntry(Material.ENCHANTED_BOOK, 75, "Quick Charge I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Quick Charge II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 300, "Quick Charge III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Multishot"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Piercing I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Piercing II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Piercing III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Piercing IV"),
                                // Tool Enchants
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Efficiency I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Efficiency II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Efficiency III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Efficiency IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Efficiency V"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Silk Touch"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Fortune I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Fortune II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Fortune III"),
                                // Universal
                                new MarketEntry(Material.ENCHANTED_BOOK, 50, "Unbreaking I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Unbreaking II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Unbreaking III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Mending"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Curse of Vanishing"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Curse of Binding"),
                                // Armor Misc
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Respiration I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Respiration II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Respiration III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Aqua Affinity"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Depth Strider I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Depth Strider II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Depth Strider III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Frost Walker I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Frost Walker II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Feather Falling I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Feather Falling II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Feather Falling III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Feather Falling IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Soul Speed I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Soul Speed II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Soul Speed III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Swift Sneak I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Swift Sneak II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 500, "Swift Sneak III"),
                                // Fishing
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Luck of the Sea I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Luck of the Sea II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Luck of the Sea III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 100, "Lure I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Lure II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 350, "Lure III"),
                                // Trident
                                new MarketEntry(Material.ENCHANTED_BOOK, 150, "Impaling I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 200, "Impaling II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 300, "Impaling III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Impaling IV"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 600, "Impaling V"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Riptide I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Riptide II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 600, "Riptide III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 250, "Loyalty I"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Loyalty II"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 600, "Loyalty III"),
                                new MarketEntry(Material.ENCHANTED_BOOK, 400, "Channeling"));

                // --- 1.21.11 NATURE ITEMS (Dynamic) ---
                String[] natureItems = {
                                "FIREFLY_BUSH:10", "LEAF_LITTER:5", "CACTUS_FLOWER:10", "WILDFLOWERS:5",
                                "SHORT_DRY_GRASS:2", "TALL_DRY_GRASS:2"
                };
                for (String itemStr : natureItems) {
                        String[] parts = itemStr.split(":");
                        Material mat = Material.matchMaterial(parts[0]);
                        if (mat != null) {
                                items.get(Category.NATURE).add(new MarketEntry(mat, Double.parseDouble(parts[1])));
                        }
                }

                // --- 1.21.11 COPPER ITEMS (Dynamic) ---
                String[] copperItems = {
                                "COPPER_NUGGET:5",
                                "COPPER_CHEST:50", "EXPOSED_COPPER_CHEST:50", "WEATHERED_COPPER_CHEST:50",
                                "OXIDIZED_COPPER_CHEST:50",
                                "WAXED_COPPER_CHEST:55", "WAXED_EXPOSED_COPPER_CHEST:55",
                                "WAXED_WEATHERED_COPPER_CHEST:55", "WAXED_OXIDIZED_COPPER_CHEST:55",
                                "COPPER_LANTERN:20", "EXPOSED_COPPER_LANTERN:20", "WEATHERED_COPPER_LANTERN:20",
                                "OXIDIZED_COPPER_LANTERN:20",
                                "WAXED_COPPER_LANTERN:25", "WAXED_EXPOSED_COPPER_LANTERN:25",
                                "WAXED_WEATHERED_COPPER_LANTERN:25", "WAXED_OXIDIZED_COPPER_LANTERN:25",
                                "COPPER_TORCH:10",
                                "COPPER_CHAIN:10", "EXPOSED_COPPER_CHAIN:10", "WEATHERED_COPPER_CHAIN:10",
                                "OXIDIZED_COPPER_CHAIN:10",
                                "WAXED_COPPER_CHAIN:12", "WAXED_EXPOSED_COPPER_CHAIN:12",
                                "WAXED_WEATHERED_COPPER_CHAIN:12", "WAXED_OXIDIZED_COPPER_CHAIN:12",
                                "COPPER_BARS:15", "EXPOSED_COPPER_BARS:15", "WEATHERED_COPPER_BARS:15",
                                "OXIDIZED_COPPER_BARS:15",
                                "WAXED_COPPER_BARS:18", "WAXED_EXPOSED_COPPER_BARS:18",
                                "WAXED_WEATHERED_COPPER_BARS:18", "WAXED_OXIDIZED_COPPER_BARS:18"
                };
                for (String itemStr : copperItems) {
                        String[] parts = itemStr.split(":");
                        Material mat = Material.matchMaterial(parts[0]);
                        if (mat != null) {
                                items.get(Category.COPPER).add(new MarketEntry(mat, Double.parseDouble(parts[1])));
                        }
                }
        }

        private static void add(Category cat, MarketEntry... entries) {
                items.get(cat).addAll(Arrays.asList(entries));
        }

        public static List<MarketEntry> getItems(Category cat) {
                return items.getOrDefault(cat, new ArrayList<>());
        }

        // --- Order-Only Items (not sold in Market, but can be requested via Buy
        // Orders) ---
        private static final Map<Category, List<MarketEntry>> orderOnlyItems = new EnumMap<>(Category.class);

        static {
                for (Category cat : Category.values()) {
                        orderOnlyItems.put(cat, new ArrayList<>());
                }

                // Smelted Ores (order-only)
                addOrderOnly(Category.MINERALS_ORES,
                                new MarketEntry(Material.COPPER_INGOT, 120),
                                new MarketEntry(Material.IRON_INGOT, 250),
                                new MarketEntry(Material.GOLD_INGOT, 600),
                                new MarketEntry(Material.NETHERITE_SCRAP, 7500),
                                new MarketEntry(Material.NETHERITE_INGOT, 30000));
        }

        private static void addOrderOnly(Category cat, MarketEntry... entries) {
                orderOnlyItems.get(cat).addAll(Arrays.asList(entries));
        }

        /**
         * Returns market items + order-only items merged for use in the Order GUI.
         */
        public static List<MarketEntry> getOrderItems(Category category) {
                // If it's a specific category, just return the items exactly as they are
                // configured
                return items.getOrDefault(category, new ArrayList<>());
        }

        public static boolean isObtainable(Material material) {
                String name = material.name();

                // Exclude strictly un-obtainable or admin/creative items
                if (name.contains("COMMAND_BLOCK") || name.contains("SPAWN_EGG") || name.contains("INFESTED") ||
                                name.equals("BEDROCK") || name.equals("BARRIER") || name.equals("STRUCTURE_BLOCK") ||
                                name.equals("STRUCTURE_VOID") || name.equals("JIGSAW") || name.equals("LIGHT") ||
                                name.equals("DEBUG_STICK") || name.equals("KNOWLEDGE_BOOK")
                                || name.equals("PETRIFIED_OAK_SLAB") ||
                                name.equals("DIRT_PATH") || name.equals("FARMLAND") || name.equals("CHORUS_PLANT") ||
                                name.equals("BUDDING_AMETHYST") || name.equals("REINFORCED_DEEPSLATE")
                                || name.equals("TRIAL_SPAWNER") ||
                                name.equals("VAULT") || name.equals("OMINOUS_VAULT") || name.equals("SPAWNER")
                                || name.equals("END_PORTAL_FRAME")) {
                        return false;
                }

                return true;
        }
}

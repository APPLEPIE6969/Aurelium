package com.aureleconomy.market;

import org.bukkit.Material;
import java.math.BigDecimal;
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
                ENCHANTMENTS(Material.ENCHANTED_BOOK, "Enchantment Books"),
                CUSTOM_ITEMS(Material.NETHER_STAR, "Custom Items"),
                ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");

                public final Material icon;
                public final String name;

                Category(Material icon, String name) {
                        this.icon = icon;
                        this.name = name;
                }

                public Material getIcon() {
                        return icon;
                }

                public String getName() {
                        return name;
                }
        }

        public static class MarketEntry {
                public final Material material;
                public final String customName;
                public final BigDecimal price;
                public BigDecimal customSellPrice;

                public MarketEntry(Material material, BigDecimal price) {
                        this.material = material;
                        this.customName = null;
                        this.price = price;
                        this.customSellPrice = null;
                }

                public MarketEntry(Material material, String customName, BigDecimal price) {
                        this.material = material;
                        this.customName = customName;
                        this.price = price;
                        this.customSellPrice = null;
                }

                public MarketEntry(Material material, BigDecimal price, BigDecimal customSellPrice) {
                        this.material = material;
                        this.customName = null;
                        this.price = price;
                        this.customSellPrice = customSellPrice;
                }

                public MarketEntry(Material material, String customName, BigDecimal price, BigDecimal customSellPrice) {
                        this.material = material;
                        this.customName = customName;
                        this.price = price;
                        this.customSellPrice = customSellPrice;
                }
        }

        private static final Map<Category, List<MarketEntry>> ITEMS = new EnumMap<>(Category.class);
        private static final Map<Category, List<MarketEntry>> ORDER_ITEMS = new EnumMap<>(Category.class);

        static {
                // TOOLS & WEAPONS
                List<MarketEntry> tools = new ArrayList<>();
                tools.add(new MarketEntry(Material.DIAMOND_SWORD, BigDecimal.valueOf(50.0)));
                tools.add(new MarketEntry(Material.DIAMOND_AXE, BigDecimal.valueOf(40.0)));
                tools.add(new MarketEntry(Material.DIAMOND_PICKAXE, BigDecimal.valueOf(35.0)));
                tools.add(new MarketEntry(Material.DIAMOND_SHOVEL, BigDecimal.valueOf(20.0)));
                tools.add(new MarketEntry(Material.DIAMOND_HOE, BigDecimal.valueOf(20.0)));
                tools.add(new MarketEntry(Material.NETHERITE_SWORD, BigDecimal.valueOf(200.0)));
                tools.add(new MarketEntry(Material.NETHERITE_AXE, BigDecimal.valueOf(180.0)));
                tools.add(new MarketEntry(Material.NETHERITE_PICKAXE, BigDecimal.valueOf(160.0)));
                tools.add(new MarketEntry(Material.NETHERITE_SHOVEL, BigDecimal.valueOf(100.0)));
                tools.add(new MarketEntry(Material.NETHERITE_HOE, BigDecimal.valueOf(100.0)));
                tools.add(new MarketEntry(Material.IRON_SWORD, BigDecimal.valueOf(10.0)));
                tools.add(new MarketEntry(Material.IRON_AXE, BigDecimal.valueOf(8.0)));
                tools.add(new MarketEntry(Material.IRON_PICKAXE, BigDecimal.valueOf(7.0)));
                tools.add(new MarketEntry(Material.IRON_SHOVEL, BigDecimal.valueOf(4.0)));
                tools.add(new MarketEntry(Material.IRON_HOE, BigDecimal.valueOf(4.0)));
                tools.add(new MarketEntry(Material.GOLDEN_SWORD, BigDecimal.valueOf(5.0)));
                tools.add(new MarketEntry(Material.GOLDEN_AXE, BigDecimal.valueOf(4.0)));
                tools.add(new MarketEntry(Material.GOLDEN_PICKAXE, BigDecimal.valueOf(3.0)));
                tools.add(new MarketEntry(Material.GOLDEN_SHOVEL, BigDecimal.valueOf(2.0)));
                tools.add(new MarketEntry(Material.GOLDEN_HOE, BigDecimal.valueOf(2.0)));
                tools.add(new MarketEntry(Material.STONE_SWORD, BigDecimal.valueOf(2.0)));
                tools.add(new MarketEntry(Material.STONE_AXE, BigDecimal.valueOf(1.5)));
                tools.add(new MarketEntry(Material.STONE_PICKAXE, BigDecimal.valueOf(1.0)));
                tools.add(new MarketEntry(Material.STONE_SHOVEL, BigDecimal.valueOf(0.5)));
                tools.add(new MarketEntry(Material.STONE_HOE, BigDecimal.valueOf(0.5)));
                tools.add(new MarketEntry(Material.WOODEN_SWORD, BigDecimal.valueOf(1.0)));
                tools.add(new MarketEntry(Material.WOODEN_AXE, BigDecimal.valueOf(0.75)));
                tools.add(new MarketEntry(Material.WOODEN_PICKAXE, BigDecimal.valueOf(0.5)));
                tools.add(new MarketEntry(Material.WOODEN_SHOVEL, BigDecimal.valueOf(0.25)));
                tools.add(new MarketEntry(Material.WOODEN_HOE, BigDecimal.valueOf(0.25)));
                tools.add(new MarketEntry(Material.BOW, BigDecimal.valueOf(15.0)));
                tools.add(new MarketEntry(Material.CROSSBOW, BigDecimal.valueOf(25.0)));
                tools.add(new MarketEntry(Material.SHIELD, BigDecimal.valueOf(12.0)));
                tools.add(new MarketEntry(Material.TRIDENT, BigDecimal.valueOf(100.0)));
                tools.add(new MarketEntry(Material.MACE, BigDecimal.valueOf(150.0)));
                ITEMS.put(Category.TOOLS_WEAPONS, tools);

                // FOOD & FARMING
                List<MarketEntry> food = new ArrayList<>();
                food.add(new MarketEntry(Material.GOLDEN_APPLE, BigDecimal.valueOf(20.0)));
                food.add(new MarketEntry(Material.ENCHANTED_GOLDEN_APPLE, BigDecimal.valueOf(200.0)));
                food.add(new MarketEntry(Material.GOLDEN_CARROT, BigDecimal.valueOf(15.0)));
                food.add(new MarketEntry(Material.APPLE, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.BREAD, BigDecimal.valueOf(1.0)));
                food.add(new MarketEntry(Material.COOKED_BEEF, BigDecimal.valueOf(3.0)));
                food.add(new MarketEntry(Material.COOKED_PORKCHOP, BigDecimal.valueOf(3.0)));
                food.add(new MarketEntry(Material.COOKED_CHICKEN, BigDecimal.valueOf(2.0)));
                food.add(new MarketEntry(Material.COOKED_MUTTON, BigDecimal.valueOf(3.0)));
                food.add(new MarketEntry(Material.COOKED_RABBIT, BigDecimal.valueOf(2.0)));
                food.add(new MarketEntry(Material.COOKED_COD, BigDecimal.valueOf(2.0)));
                food.add(new MarketEntry(Material.COOKED_SALMON, BigDecimal.valueOf(3.0)));
                food.add(new MarketEntry(Material.BAKED_POTATO, BigDecimal.valueOf(1.5)));
                food.add(new MarketEntry(Material.CARROT, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.POTATO, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.BEETROOT, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.MELON_SLICE, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.GLOW_BERRIES, BigDecimal.valueOf(1.0)));
                food.add(new MarketEntry(Material.SWEET_BERRIES, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.HONEY_BOTTLE, BigDecimal.valueOf(5.0)));
                food.add(new MarketEntry(Material.PUMPKIN_PIE, BigDecimal.valueOf(2.0)));
                food.add(new MarketEntry(Material.COOKIE, BigDecimal.valueOf(0.5)));
                food.add(new MarketEntry(Material.CAKE, BigDecimal.valueOf(5.0)));
                food.add(new MarketEntry(Material.SUSPICIOUS_STEW, BigDecimal.valueOf(2.0)));
                food.add(new MarketEntry(Material.RABBIT_STEW, BigDecimal.valueOf(4.0)));
                food.add(new MarketEntry(Material.MUSHROOM_STEW, BigDecimal.valueOf(3.0)));
                food.add(new MarketEntry(Material.BEETROOT_SOUP, BigDecimal.valueOf(2.0)));
                food.add(new MarketEntry(Material.HAY_BLOCK, BigDecimal.valueOf(5.0)));
                food.add(new MarketEntry(Material.WHEAT, BigDecimal.valueOf(0.5)));
                ITEMS.put(Category.FOOD_FARMING, food);

                // MINERALS & ORES
                List<MarketEntry> minerals = new ArrayList<>();
                minerals.add(new MarketEntry(Material.DIAMOND, BigDecimal.valueOf(25.0)));
                minerals.add(new MarketEntry(Material.EMERALD, BigDecimal.valueOf(15.0)));
                minerals.add(new MarketEntry(Material.NETHERITE_INGOT, BigDecimal.valueOf(50.0)));
                minerals.add(new MarketEntry(Material.NETHERITE_SCRAP, BigDecimal.valueOf(30.0)));
                minerals.add(new MarketEntry(Material.ANCIENT_DEBRIS, BigDecimal.valueOf(100.0)));
                minerals.add(new MarketEntry(Material.IRON_INGOT, BigDecimal.valueOf(3.0)));
                minerals.add(new MarketEntry(Material.GOLD_INGOT, BigDecimal.valueOf(5.0)));
                minerals.add(new MarketEntry(Material.RAW_IRON, BigDecimal.valueOf(2.5)));
                minerals.add(new MarketEntry(Material.RAW_GOLD, BigDecimal.valueOf(4.0)));
                minerals.add(new MarketEntry(Material.RAW_COPPER, BigDecimal.valueOf(2.0)));
                minerals.add(new MarketEntry(Material.COPPER_INGOT, BigDecimal.valueOf(4.0)));
                minerals.add(new MarketEntry(Material.COAL, BigDecimal.valueOf(0.5)));
                minerals.add(new MarketEntry(Material.CHARCOAL, BigDecimal.valueOf(0.5)));
                minerals.add(new MarketEntry(Material.REDSTONE, BigDecimal.valueOf(1.0)));
                minerals.add(new MarketEntry(Material.LAPIS_LAZULI, BigDecimal.valueOf(2.0)));
                minerals.add(new MarketEntry(Material.QUARTZ, BigDecimal.valueOf(2.0)));
                minerals.add(new MarketEntry(Material.AMETHYST_SHARD, BigDecimal.valueOf(10.0)));
                minerals.add(new MarketEntry(Material.ECHO_SHARD, BigDecimal.valueOf(50.0)));
                minerals.add(new MarketEntry(Material.DIAMOND_BLOCK, BigDecimal.valueOf(200.0)));
                minerals.add(new MarketEntry(Material.EMERALD_BLOCK, BigDecimal.valueOf(120.0)));
                minerals.add(new MarketEntry(Material.GOLD_BLOCK, BigDecimal.valueOf(45.0)));
                minerals.add(new MarketEntry(Material.IRON_BLOCK, BigDecimal.valueOf(27.0)));
                minerals.add(new MarketEntry(Material.COPPER_BLOCK, BigDecimal.valueOf(36.0)));
                minerals.add(new MarketEntry(Material.NETHERITE_BLOCK, BigDecimal.valueOf(450.0)));
                ITEMS.put(Category.MINERALS_ORES, minerals);

                // MOB DROPS & MAGIC
                List<MarketEntry> mobDrops = new ArrayList<>();
                mobDrops.add(new MarketEntry(Material.BLAZE_ROD, BigDecimal.valueOf(8.0)));
                mobDrops.add(new MarketEntry(Material.ENDER_PEARL, BigDecimal.valueOf(10.0)));
                mobDrops.add(new MarketEntry(Material.GHAST_TEAR, BigDecimal.valueOf(15.0)));
                mobDrops.add(new MarketEntry(Material.MAGMA_CREAM, BigDecimal.valueOf(6.0)));
                mobDrops.add(new MarketEntry(Material.SHULKER_SHELL, BigDecimal.valueOf(25.0)));
                mobDrops.add(new MarketEntry(Material.PHANTOM_MEMBRANE, BigDecimal.valueOf(5.0)));
                mobDrops.add(new MarketEntry(Material.SLIME_BALL, BigDecimal.valueOf(2.0)));
                mobDrops.add(new MarketEntry(Material.CREEPER_HEAD, BigDecimal.valueOf(50.0)));
                mobDrops.add(new MarketEntry(Material.ZOMBIE_HEAD, BigDecimal.valueOf(30.0)));
                mobDrops.add(new MarketEntry(Material.SKELETON_SKULL, BigDecimal.valueOf(30.0)));
                mobDrops.add(new MarketEntry(Material.WITHER_SKELETON_SKULL, BigDecimal.valueOf(100.0)));
                mobDrops.add(new MarketEntry(Material.DRAGON_HEAD, BigDecimal.valueOf(500.0)));
                mobDrops.add(new MarketEntry(Material.PIGLIN_HEAD, BigDecimal.valueOf(20.0)));
                mobDrops.add(new MarketEntry(Material.TOTEM_OF_UNDYING, BigDecimal.valueOf(200.0)));
                mobDrops.add(new MarketEntry(Material.NAUTILUS_SHELL, BigDecimal.valueOf(20.0)));
                mobDrops.add(new MarketEntry(Material.HEART_OF_THE_SEA, BigDecimal.valueOf(150.0)));
                mobDrops.add(new MarketEntry(Material.ENCHANTED_BOOK, BigDecimal.valueOf(50.0), BigDecimal.valueOf(20.0)));
                mobDrops.add(new MarketEntry(Material.EXPERIENCE_BOTTLE, BigDecimal.valueOf(5.0)));
                mobDrops.add(new MarketEntry(Material.TURTLE_SCUTE, BigDecimal.valueOf(10.0)));
                mobDrops.add(new MarketEntry(Material.ECHO_SHARD, BigDecimal.valueOf(50.0)));
                mobDrops.add(new MarketEntry(Material.RECOVERY_COMPASS, BigDecimal.valueOf(100.0)));
                ITEMS.put(Category.MOB_DROPS, mobDrops);

                // COLORS & DYES
                List<MarketEntry> colors = new ArrayList<>();
                colors.add(new MarketEntry(Material.WHITE_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.BLACK_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.RED_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.GREEN_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.BLUE_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.YELLOW_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.LIME_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.PINK_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.GRAY_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.LIGHT_GRAY_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.CYAN_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.PURPLE_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.BROWN_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.ORANGE_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.LIGHT_BLUE_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.MAGENTA_DYE, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.INK_SAC, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.BONE_MEAL, BigDecimal.valueOf(1.0)));
                colors.add(new MarketEntry(Material.WHITE_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.BLACK_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.RED_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.GREEN_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.BLUE_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.YELLOW_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.LIME_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.PINK_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.GRAY_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.LIGHT_GRAY_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.CYAN_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.PURPLE_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.BROWN_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.ORANGE_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.LIGHT_BLUE_WOOL, BigDecimal.valueOf(2.0)));
                colors.add(new MarketEntry(Material.MAGENTA_WOOL, BigDecimal.valueOf(2.0)));
                ITEMS.put(Category.COLORS, colors);

                // BUILDING
                List<MarketEntry> building = new ArrayList<>();
                building.add(new MarketEntry(Material.STONE, BigDecimal.valueOf(0.5)));
                building.add(new MarketEntry(Material.COBBLESTONE, BigDecimal.valueOf(0.25)));
                building.add(new MarketEntry(Material.STONE_BRICKS, BigDecimal.valueOf(2.0)));
                building.add(new MarketEntry(Material.BRICKS, BigDecimal.valueOf(3.0)));
                building.add(new MarketEntry(Material.SMOOTH_STONE, BigDecimal.valueOf(1.5)));
                building.add(new MarketEntry(Material.DEEPSLATE, BigDecimal.valueOf(1.0)));
                building.add(new MarketEntry(Material.COBBLED_DEEPSLATE, BigDecimal.valueOf(0.75)));
                building.add(new MarketEntry(Material.DEEPSLATE_BRICKS, BigDecimal.valueOf(3.0)));
                building.add(new MarketEntry(Material.POLISHED_DEEPSLATE, BigDecimal.valueOf(2.5)));
                building.add(new MarketEntry(Material.SANDSTONE, BigDecimal.valueOf(1.5)));
                building.add(new MarketEntry(Material.SMOOTH_SANDSTONE, BigDecimal.valueOf(2.0)));
                building.add(new MarketEntry(Material.RED_SANDSTONE, BigDecimal.valueOf(2.0)));
                building.add(new MarketEntry(Material.PRISMARINE, BigDecimal.valueOf(5.0)));
                building.add(new MarketEntry(Material.PRISMARINE_BRICKS, BigDecimal.valueOf(6.0)));
                building.add(new MarketEntry(Material.DARK_PRISMARINE, BigDecimal.valueOf(5.0)));
                building.add(new MarketEntry(Material.QUARTZ_BLOCK, BigDecimal.valueOf(5.0)));
                building.add(new MarketEntry(Material.SMOOTH_QUARTZ_BLOCK, BigDecimal.valueOf(6.0)));
                building.add(new MarketEntry(Material.END_STONE, BigDecimal.valueOf(3.0)));
                building.add(new MarketEntry(Material.END_STONE_BRICKS, BigDecimal.valueOf(4.0)));
                building.add(new MarketEntry(Material.PURPUR_BLOCK, BigDecimal.valueOf(4.0)));
                building.add(new MarketEntry(Material.BASALT, BigDecimal.valueOf(2.0)));
                building.add(new MarketEntry(Material.POLISHED_BASALT, BigDecimal.valueOf(3.0)));
                building.add(new MarketEntry(Material.SMOOTH_BASALT, BigDecimal.valueOf(3.0)));
                building.add(new MarketEntry(Material.CALCITE, BigDecimal.valueOf(2.0)));
                building.add(new MarketEntry(Material.TUFF, BigDecimal.valueOf(1.0)));
                building.add(new MarketEntry(Material.DRIPSTONE_BLOCK, BigDecimal.valueOf(3.0)));
                building.add(new MarketEntry(Material.MUD_BRICKS, BigDecimal.valueOf(2.0)));
                building.add(new MarketEntry(Material.PACKED_MUD, BigDecimal.valueOf(2.0)));
                ITEMS.put(Category.BUILDING, building);

                // DECORATION
                List<MarketEntry> decoration = new ArrayList<>();
                decoration.add(new MarketEntry(Material.PAINTING, BigDecimal.valueOf(5.0)));
                decoration.add(new MarketEntry(Material.ITEM_FRAME, BigDecimal.valueOf(3.0)));
                decoration.add(new MarketEntry(Material.GLOW_ITEM_FRAME, BigDecimal.valueOf(10.0)));
                decoration.add(new MarketEntry(Material.FLOWER_POT, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_OAK_SAPLING, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_BIRCH_SAPLING, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_SPRUCE_SAPLING, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_JUNGLE_SAPLING, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_ACACIA_SAPLING, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_DARK_OAK_SAPLING, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_MANGROVE_PROPAGULE, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_BAMBOO, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_CACTUS, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_DEAD_BUSH, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_FERN, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_AZALEA_BUSH, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.POTTED_FLOWERING_AZALEA_BUSH, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.HANGING_ROOTS, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.CHAIN, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.LANTERN, BigDecimal.valueOf(5.0)));
                decoration.add(new MarketEntry(Material.SOUL_LANTERN, BigDecimal.valueOf(5.0)));
                decoration.add(new MarketEntry(Material.CANDLE, BigDecimal.valueOf(1.0)));
                decoration.add(new MarketEntry(Material.SEA_PICKLE, BigDecimal.valueOf(1.0)));
                decoration.add(new MarketEntry(Material.GLOW_LICHEN, BigDecimal.valueOf(1.0)));
                decoration.add(new MarketEntry(Material.VINE, BigDecimal.valueOf(1.0)));
                decoration.add(new MarketEntry(Material.MOSS_CARPET, BigDecimal.valueOf(1.0)));
                decoration.add(new MarketEntry(Material.MOSS_BLOCK, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.DRIPLEAF_PLANT, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.BIG_DRIPLEAF, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.SMALL_DRIPLEAF, BigDecimal.valueOf(1.0)));
                decoration.add(new MarketEntry(Material.AZALEA, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.FLOWERING_AZALEA, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.SPORE_BLOSSOM, BigDecimal.valueOf(2.0)));
                decoration.add(new MarketEntry(Material.GLOW_BERRIES, BigDecimal.valueOf(1.0)));
                ITEMS.put(Category.DECORATION, decoration);

                // ENCHANTMENTS
                List<MarketEntry> enchantments = new ArrayList<>();
                enchantments.add(new MarketEntry(Material.ENCHANTED_BOOK, BigDecimal.valueOf(50.0), BigDecimal.valueOf(20.0)));
                ITEMS.put(Category.ENCHANTMENTS, enchantments);

                // CUSTOM ITEMS
                List<MarketEntry> customItems = new ArrayList<>();
                ITEMS.put(Category.CUSTOM_ITEMS, customItems);

                // ALL ITEMS (for search)
                List<MarketEntry> allItems = new ArrayList<>();
                for (List<MarketEntry> list : ITEMS.values()) {
                        allItems.addAll(list);
                }
                ITEMS.put(Category.ALL_ITEMS, allItems);

                // ORDER ITEMS (simplified subsets for buy orders)
                ORDER_ITEMS.put(Category.TOOLS_WEAPONS, tools.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(10.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.FOOD_FARMING, food.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(2.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.MINERALS_ORES, minerals.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(5.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.MOB_DROPS, mobDrops.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(5.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.COLORS, colors.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(1.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.BUILDING, building.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(1.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.DECORATION, decoration.stream().filter(e -> e.price.compareTo(BigDecimal.valueOf(1.0)) >= 0).toList());
                ORDER_ITEMS.put(Category.ENCHANTMENTS, enchantments);
                ORDER_ITEMS.put(Category.CUSTOM_ITEMS, new ArrayList<>());
                ORDER_ITEMS.put(Category.ALL_ITEMS, new ArrayList<>());
        }

        public static List<MarketEntry> getItems(Category category) {
                return ITEMS.getOrDefault(category, new ArrayList<>());
        }

        public static List<MarketEntry> getOrderItems(Category category) {
                return ORDER_ITEMS.getOrDefault(category, new ArrayList<>());
        }

        public static List<Category> getCategories() {
                return Arrays.asList(Category.values());
        }
}
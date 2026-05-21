package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems.Category;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Level;

/**
 * Unified item scanner implementing all 6 detection methods.
 * Uses reflection for all soft-depend plugin APIs to avoid compile-time dependencies.
 */
public class UnifiedItemScanner {

    private final AurelEconomy plugin;
    private final CustomItemRegistry registry;

    // Config flags
    private final boolean methodPluginApi;
    private final boolean methodPdcScan;
    private final boolean methodCustomModelData;
    private final boolean methodLorePattern;
    private final boolean methodInventoryScan;
    private final boolean methodInteractionDetect;
    private final Set<String> excludedNamespaces;
    private final double defaultPriceMultiplier;

    public UnifiedItemScanner(AurelEconomy plugin, CustomItemRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;

        this.methodPluginApi = plugin.getConfig().getBoolean("custom-items.discovery-methods.plugin-api", true);
        this.methodPdcScan = plugin.getConfig().getBoolean("custom-items.discovery-methods.pdc-scan", true);
        this.methodCustomModelData = plugin.getConfig().getBoolean("custom-items.discovery-methods.custom-model-data", true);
        this.methodLorePattern = plugin.getConfig().getBoolean("custom-items.discovery-methods.lore-pattern", true);
        this.methodInventoryScan = plugin.getConfig().getBoolean("custom-items.discovery-methods.inventory-scan", true);
        this.methodInteractionDetect = plugin.getConfig().getBoolean("custom-items.discovery-methods.interaction-detect", true);
        this.excludedNamespaces = new HashSet<>(plugin.getConfig().getStringList("custom-items.excluded-namespaces"));
        if (excludedNamespaces.isEmpty()) {
            excludedNamespaces.add("minecraft");
            excludedNamespaces.add("aureleconomy");
        }
        this.defaultPriceMultiplier = plugin.getConfig().getDouble("custom-items.default-price-multiplier", 1.5);
    }

    // ===== METHOD 1: Plugin-Specific API Scanning =====

    public void scanAllPluginAPIs() {
        if (!methodPluginApi) return;
        scanItemsAdder();
        scanOraxen();
        scanMMOItems();
        scanMythicMobs();
        scanExecutableItems();
        scanNexo();
        scanSXItem();
    }

    private void scanItemsAdder() {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return;
        try {
            Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method getItemsMethod = customStackClass.getMethod("getItems");
            Method getItemStackMethod = customStackClass.getMethod("getItemStack");
            Method getNamespacedIDMethod = customStackClass.getMethod("getNamespacedID");

            @SuppressWarnings("unchecked")
            Map<String, ?> items = (Map<String, ?>) getItemsMethod.invoke(null);
            if (items == null) return;

            for (Map.Entry<String, ?> entry : items.entrySet()) {
                try {
                    Object customStack = entry.getValue();
                    ItemStack itemStack = (ItemStack) getItemStackMethod.invoke(customStack);
                    String nativeId = (String) getNamespacedIDMethod.invoke(customStack);
                    if (itemStack == null || nativeId == null) continue;

                    CustomMarketItem item = buildCustomItem(itemStack, nativeId, "ItemsAdder",
                            DiscoveryMethod.PLUGIN_API_ITEMSADDER);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.FINE, "[Scanner] ItemsAdder item scan error", e);
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Plugin not present or API changed, skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] ItemsAdder scan failed: " + e.getMessage());
        }
    }

    private void scanOraxen() {
        if (Bukkit.getPluginManager().getPlugin("Oraxen") == null) return;
        try {
            Class<?> oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Method getItemsMethod = oraxenItemsClass.getMethod("getItems");
            Method getItemByIdMethod = oraxenItemsClass.getMethod("getItemById", String.class);
            Method buildMethod = Class.forName("io.th0rgal.oraxen.items.model.ItemBuilder").getMethod("build");

            @SuppressWarnings("unchecked")
            Set<String> ids = (Set<String>) getItemsMethod.invoke(null);
            if (ids == null) return;

            for (String id : ids) {
                try {
                    Object itemBuilder = getItemByIdMethod.invoke(null, id);
                    if (itemBuilder == null) continue;
                    ItemStack itemStack = (ItemStack) buildMethod.invoke(itemBuilder);
                    if (itemStack == null) continue;

                    CustomMarketItem item = buildCustomItem(itemStack, "oraxen:" + id, "Oraxen",
                            DiscoveryMethod.PLUGIN_API_ORAXEN);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_ORAXEN);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.FINE, "[Scanner] Oraxen item scan error", e);
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] Oraxen scan failed: " + e.getMessage());
        }
    }

    private void scanMMOItems() {
        if (Bukkit.getPluginManager().getPlugin("MMOItems") == null) return;
        try {
            Class<?> mmoItemsPlugin = Class.forName("net.Indyuce.mmoitems.MMOItems");
            Method getPluginMethod = mmoItemsPlugin.getMethod("getPlugin");
            Object mmoPlugin = getPluginMethod.invoke(null);

            Class<?> itemManagerClass = Class.forName("net.Indyuce.mmoitems.manager.ItemManager");
            Class<?> typeClass = Class.forName("net.Indyuce.mmoitems.api.Type");
            Method getTypesMethod = itemManagerClass.getMethod("getAll");

            // Access MMOItems.getPlugin().getTypes().getAll()
            Method getItemManagerMethod = mmoItemsPlugin.getMethod("getItems");
            Object itemManager = getItemManagerMethod.invoke(mmoPlugin);

            @SuppressWarnings("unchecked")
            Collection<?> types = (Collection<?>) getTypesMethod.invoke(itemManager);
            if (types == null) return;

            Method getIdMethod = typeClass.getMethod("getId");
            Method getAllItemsMethod = itemManagerClass.getMethod("getAll", typeClass);

            for (Object type : types) {
                try {
                    String typeId = (String) getIdMethod.invoke(type);
                    @SuppressWarnings("unchecked")
                    Map<String, ?> items = (Map<String, ?>) getAllItemsMethod.invoke(itemManager, type);
                    if (items == null) continue;

                    for (Map.Entry<String, ?> entry : items.entrySet()) {
                        try {
                            Object mmoItem = entry.getValue();
                            Method newItemStackMethod = mmoItem.getClass().getMethod("newItemStack");
                            ItemStack itemStack = (ItemStack) newItemStackMethod.invoke(mmoItem);
                            if (itemStack == null) continue;

                            String nativeId = typeId.toLowerCase() + ":" + entry.getKey().toLowerCase();
                            CustomMarketItem item = buildCustomItem(itemStack, nativeId, "MMOItems",
                                    DiscoveryMethod.PLUGIN_API_MMOITEMS);
                            registry.register(item, DiscoveryMethod.PLUGIN_API_MMOITEMS);
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] MMOItems scan failed: " + e.getMessage());
        }
    }

    private void scanMythicMobs() {
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") == null) return;
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter");
            Class<?> mythicPluginClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Method instMethod = mythicPluginClass.getMethod("inst");
            Object inst = instMethod.invoke(null);

            Method getItemManagerMethod = mythicPluginClass.getMethod("getItemManager");
            Object itemManager = getItemManagerMethod.invoke(inst);

            Method getItemNamesMethod = itemManager.getClass().getMethod("getItemNames");
            Method getItemStackMethod = itemManager.getClass().getMethod("getItemStack", String.class);

            @SuppressWarnings("unchecked")
            Collection<String> names = (Collection<String>) getItemNamesMethod.invoke(itemManager);
            if (names == null) return;

            for (String name : names) {
                try {
                    Object optItem = getItemStackMethod.invoke(itemManager, name);
                    if (optItem == null) continue;
                    // MythicMobs returns Optional<ItemStack>
                    Method orElseMethod = optItem.getClass().getMethod("orElse", Object.class);
                    ItemStack itemStack = (ItemStack) orElseMethod.invoke(optItem, (Object) null);
                    if (itemStack == null) continue;

                    CustomMarketItem item = buildCustomItem(itemStack, "mythicmobs:" + name, "MythicMobs",
                            DiscoveryMethod.PLUGIN_API_MYTHICMOBS);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_MYTHICMOBS);
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] MythicMobs scan failed: " + e.getMessage());
        }
    }

    private void scanExecutableItems() {
        if (Bukkit.getPluginManager().getPlugin("ExecutableItems") == null) return;
        try {
            Class<?> eiPluginClass = Class.forName("com.ssomar.executableitems.ExecutableItems");
            Method getPluginMethod = eiPluginClass.getMethod("getPlugin");
            Object eiPlugin = getPluginMethod.invoke(null);

            Method getItemManagerMethod = eiPluginClass.getMethod("getItemManager");
            Object itemManager = getItemManagerMethod.invoke(eiPlugin);

            Method getAllItemsMethod = itemManager.getClass().getMethod("getAllItems");
            @SuppressWarnings("unchecked")
            Collection<?> items = (Collection<?>) getAllItemsMethod.invoke(itemManager);
            if (items == null) return;

            for (Object eiItem : items) {
                try {
                    Method getIdMethod = eiItem.getClass().getMethod("getId");
                    Method buildItemMethod = eiItem.getClass().getMethod("buildItem", int.class);
                    String id = (String) getIdMethod.invoke(eiItem);
                    ItemStack itemStack = (ItemStack) buildItemMethod.invoke(eiItem, 1);
                    if (itemStack == null || id == null) continue;

                    CustomMarketItem item = buildCustomItem(itemStack, "executableitems:" + id, "ExecutableItems",
                            DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] ExecutableItems scan failed: " + e.getMessage());
        }
    }

    private void scanNexo() {
        if (Bukkit.getPluginManager().getPlugin("Nexo") == null) return;
        try {
            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.items.NexoItems");
            Method valuesMethod = nexoItemsClass.getMethod("values");
            Method getItemStackMethod = nexoItemsClass.getMethod("getItemStack");

            @SuppressWarnings("unchecked")
            Collection<?> items = (Collection<?>) valuesMethod.invoke(null);
            if (items == null) return;

            Method getIdMethod = nexoItemsClass.getMethod("getId");
            for (Object nexoItem : items) {
                try {
                    ItemStack itemStack = (ItemStack) getItemStackMethod.invoke(nexoItem);
                    String id = (String) getIdMethod.invoke(nexoItem);
                    if (itemStack == null || id == null) continue;

                    CustomMarketItem item = buildCustomItem(itemStack, "nexo:" + id, "Nexo",
                            DiscoveryMethod.PLUGIN_API_NEXO);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_NEXO);
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] Nexo scan failed: " + e.getMessage());
        }
    }

    private void scanSXItem() {
        if (Bukkit.getPluginManager().getPlugin("SX-Item") == null) return;
        try {
            Class<?> sxPluginClass = Class.forName("com.sucy.sxitem.SXItem");
            Method getPluginMethod = sxPluginClass.getMethod("getPlugin");
            Object sxPlugin = getPluginMethod.invoke(null);

            Method getItemManagerMethod = sxPluginClass.getMethod("getItemManager");
            Object itemManager = getItemManagerMethod.invoke(sxPlugin);

            Method getAllItemsMethod = itemManager.getClass().getMethod("getAllItems");
            @SuppressWarnings("unchecked")
            Map<String, ?> items = (Map<String, ?>) getAllItemsMethod.invoke(itemManager);
            if (items == null) return;

            for (Map.Entry<String, ?> entry : items.entrySet()) {
                try {
                    Method createItemMethod = entry.getValue().getClass().getMethod("createItem");
                    ItemStack itemStack = (ItemStack) createItemMethod.invoke(entry.getValue());
                    if (itemStack == null) continue;

                    CustomMarketItem item = buildCustomItem(itemStack, "sxitem:" + entry.getKey(), "SX-Item",
                            DiscoveryMethod.PLUGIN_API_SX_ITEM);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_SX_ITEM);
                } catch (Exception ignored) {}
            }
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] SX-Item scan failed: " + e.getMessage());
        }
    }

    // ===== METHOD 2: PersistentDataContainer Scan =====

    public void scanViaPDC(ItemStack item) {
        if (!methodPdcScan) return;
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;

        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        if (pdc.isEmpty()) return;

        for (NamespacedKey key : pdc.getKeys()) {
            String ns = key.getNamespace();
            if (excludedNamespaces.contains(ns)) continue;

            String pdcKey = ns + ":" + key.getKey();
            String canonicalId = ns + ":" + item.getType().name().toLowerCase() + "_" + key.getKey();

            String displayName = resolveDisplayName(item);
            BigDecimal basePrice = estimatePrice(item);
            BigDecimal buyPrice = basePrice.multiply(BigDecimal.valueOf(defaultPriceMultiplier));

            CustomMarketItem customItem = new CustomMarketItem.Builder()
                    .canonicalId(canonicalId)
                    .itemStack(item)
                    .sourcePlugin(detectPluginFromNamespace(ns))
                    .displayName(displayName)
                    .pdcKey(pdcKey)
                    .modelDataKey(extractModelDataKey(item))
                    .loreHash(extractLoreHash(item))
                    .category(autoAssignCategory(item).name())
                    .buyPrice(buyPrice)
                    .sellPrice(basePrice)
                    .build();

            registry.register(customItem, DiscoveryMethod.PDC_SCAN);
        }
    }

    // ===== METHOD 3: CustomModelData Scan =====

    public void scanViaModelData(ItemStack item) {
        if (!methodCustomModelData) return;
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasCustomModelData() || meta.getCustomModelData() <= 0) return;

        String modelDataKey = item.getType().name() + ":" + meta.getCustomModelData();
        String canonicalId = "modeldata:" + modelDataKey.toLowerCase();

        String displayName = resolveDisplayName(item);
        BigDecimal basePrice = estimatePrice(item);
        BigDecimal buyPrice = basePrice.multiply(BigDecimal.valueOf(defaultPriceMultiplier));

        CustomMarketItem customItem = new CustomMarketItem.Builder()
                .canonicalId(canonicalId)
                .itemStack(item)
                .sourcePlugin("CustomModelData")
                .displayName(displayName)
                .pdcKey(extractPdcKey(item))
                .modelDataKey(modelDataKey)
                .loreHash(extractLoreHash(item))
                .category(autoAssignCategory(item).name())
                .buyPrice(buyPrice)
                .sellPrice(basePrice)
                .build();

        registry.register(customItem, DiscoveryMethod.CUSTOM_MODEL_DATA);
    }

    // ===== METHOD 4: Lore Pattern Scan =====

    public void scanViaLore(ItemStack item) {
        if (!methodLorePattern) return;
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore() || meta.lore() == null || meta.lore().isEmpty()) return;

        List<net.kyori.adventure.text.Component> lore = meta.lore();
        boolean isCustom = false;

        // Check for hex color patterns, common custom item identifiers
        for (net.kyori.adventure.text.Component line : lore) {
            String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(line);
            // Hex color indicator
            if (plain.contains("\u00a7x")) { isCustom = true; break; }
            // Common custom item markers
            if (plain.contains("CustomItem:") || plain.contains("ItemsAdder") || plain.contains("Oraxen")
                    || plain.contains("MMOItems") || plain.contains("ExecutableItems")) {
                isCustom = true; break;
            }
        }

        // Also flag items with PDC keys from non-minecraft namespaces as custom via lore
        if (!isCustom) {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                if (!excludedNamespaces.contains(key.getNamespace())) {
                    isCustom = true;
                    break;
                }
            }
        }

        if (!isCustom) return;

        String loreHash = String.valueOf(lore.hashCode());
        String canonicalId = "lore:" + item.getType().name().toLowerCase() + "_" + loreHash;

        String displayName = resolveDisplayName(item);
        BigDecimal basePrice = estimatePrice(item);
        BigDecimal buyPrice = basePrice.multiply(BigDecimal.valueOf(defaultPriceMultiplier));

        CustomMarketItem customItem = new CustomMarketItem.Builder()
                .canonicalId(canonicalId)
                .itemStack(item)
                .sourcePlugin("LorePattern")
                .displayName(displayName)
                .pdcKey(extractPdcKey(item))
                .modelDataKey(extractModelDataKey(item))
                .loreHash(loreHash)
                .category(autoAssignCategory(item).name())
                .buyPrice(buyPrice)
                .sellPrice(basePrice)
                .build();

        registry.register(customItem, DiscoveryMethod.LORE_PATTERN);
    }

    // ===== METHOD 5: Player Inventory Scan =====

    public void scanPlayerInventories() {
        if (!methodInventoryScan) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                scanSingleItem(item, DiscoveryMethod.INVENTORY_SCAN);
            }
            for (ItemStack item : player.getEnderChest().getContents()) {
                scanSingleItem(item, DiscoveryMethod.INVENTORY_SCAN);
            }
        }
    }

    // ===== METHOD 6: Scan single item (used by interaction detection + inventory scan) =====

    public void scanSingleItem(ItemStack item, DiscoveryMethod method) {
        if (item == null || item.getType().isAir()) return;

        // Apply all 3 passive scan methods
        scanViaPDC(item);
        scanViaModelData(item);
        scanViaLore(item);

        // Also try to match against plugin APIs by item
        if (methodPluginApi) {
            scanItemAgainstPluginAPIs(item);
        }
    }

    /**
     * Try to identify an item against each plugin's byItemStack-style API.
     */
    private void scanItemAgainstPluginAPIs(ItemStack item) {
        // ItemsAdder
        try {
            Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            Method byItemStack = customStackClass.getMethod("byItemStack", ItemStack.class);
            Object result = byItemStack.invoke(null, item);
            if (result != null) {
                Method getId = customStackClass.getMethod("getNamespacedID");
                String id = (String) getId.invoke(result);
                if (id != null) {
                    CustomMarketItem customItem = buildCustomItem(item, id, "ItemsAdder",
                            DiscoveryMethod.PLUGIN_API_ITEMSADDER);
                    registry.register(customItem, DiscoveryMethod.PLUGIN_API_ITEMSADDER);
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        } catch (Exception ignored) {}

        // Oraxen
        try {
            Class<?> oraxenItemsClass = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Method getIdByItem = oraxenItemsClass.getMethod("getIdByItem", ItemStack.class);
            String id = (String) getIdByItem.invoke(null, item);
            if (id != null && !id.isEmpty()) {
                CustomMarketItem customItem = buildCustomItem(item, "oraxen:" + id, "Oraxen",
                        DiscoveryMethod.PLUGIN_API_ORAXEN);
                registry.register(customItem, DiscoveryMethod.PLUGIN_API_ORAXEN);
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        } catch (Exception ignored) {}

        // Nexo
        try {
            Class<?> nexoItemsClass = Class.forName("com.nexomc.nexo.items.NexoItems");
            Method getByItem = nexoItemsClass.getMethod("getByItem", ItemStack.class);
            Object result = getByItem.invoke(null, item);
            if (result != null) {
                Method getId = result.getClass().getMethod("getId");
                String id = (String) getId.invoke(result);
                if (id != null) {
                    CustomMarketItem customItem = buildCustomItem(item, "nexo:" + id, "Nexo",
                            DiscoveryMethod.PLUGIN_API_NEXO);
                    registry.register(customItem, DiscoveryMethod.PLUGIN_API_NEXO);
                }
            }
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
        } catch (Exception ignored) {}
    }

    // ===== Utility Methods =====

    /**
     * Build a CustomMarketItem from an ItemStack and its plugin metadata.
     * Populates ALL dedup keys for maximum dedup efficiency.
     */
    private CustomMarketItem buildCustomItem(ItemStack item, String nativeId, String sourcePlugin,
                                               DiscoveryMethod method) {
        String displayName = resolveDisplayName(item);
        BigDecimal basePrice = estimatePrice(item);
        BigDecimal buyPrice = basePrice.multiply(BigDecimal.valueOf(defaultPriceMultiplier));

        return new CustomMarketItem.Builder()
                .canonicalId(nativeId)
                .itemStack(item)
                .sourcePlugin(sourcePlugin)
                .displayName(displayName)
                .pdcKey(extractPdcKey(item))
                .modelDataKey(extractModelDataKey(item))
                .loreHash(extractLoreHash(item))
                .pluginNativeId(nativeId)
                .category(autoAssignCategory(item).name())
                .buyPrice(buyPrice)
                .sellPrice(basePrice)
                .build();
    }

    /**
     * Extract the first non-excluded PDC key as "namespace:key", or null.
     */
    public String extractPdcKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (!excludedNamespaces.contains(key.getNamespace())) {
                return key.getNamespace() + ":" + key.getKey();
            }
        }
        return null;
    }

    /**
     * Extract "MATERIAL:12345" from custom model data, or null.
     */
    public String extractModelDataKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasCustomModelData() && meta.getCustomModelData() > 0) {
            return item.getType().name() + ":" + meta.getCustomModelData();
        }
        return null;
    }

    /**
     * Extract lore hash as string, or null.
     */
    public String extractLoreHash(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta.hasLore() && meta.lore() != null && !meta.lore().isEmpty()) {
            return String.valueOf(meta.lore().hashCode());
        }
        return null;
    }

    /**
     * Map common PDC namespaces to plugin names.
     */
    public String detectPluginFromNamespace(String namespace) {
        return switch (namespace) {
            case "itemsadder" -> "ItemsAdder";
            case "oraxen" -> "Oraxen";
            case "mmoitems" -> "MMOItems";
            case "mythicmobs" -> "MythicMobs";
            case "executableitems" -> "ExecutableItems";
            case "nexo" -> "Nexo";
            case "sxitem" -> "SX-Item";
            default -> namespace;
        };
    }

    /**
     * Auto-assign a market category based on item material.
     */
    public Category autoAssignCategory(ItemStack item) {
        if (item == null) return Category.CUSTOM_ITEMS;
        Material mat = item.getType();

        if (mat.name().contains("SWORD") || mat.name().contains("AXE") || mat.name().contains("PICKAXE")
                || mat.name().contains("SHOVEL") || mat.name().contains("HOE") || mat.name().contains("BOW")
                || mat.name().contains("TRIDENT") || mat.name().contains("MACE") || mat.name().contains("CROSSBOW"))
            return Category.TOOLS_WEAPONS;
        if (mat.isEdible() || mat.name().contains("SEED") || mat.name().contains("CROP")
                || mat.name().contains("WHEAT") || mat.name().contains("CARROT") || mat.name().contains("POTATO"))
            return Category.FOOD_FARMING;
        if (mat.name().contains("DIAMOND") || mat.name().contains("EMERALD") || mat.name().contains("GOLD")
                || mat.name().contains("IRON") || mat.name().contains("LAPIS") || mat.name().contains("REDSTONE")
                || mat.name().contains("QUARTZ") || mat.name().contains("AMETHYST") || mat.name().contains("NETHERITE"))
            return Category.MINERALS_ORES;
        if (mat.name().contains("SPAWN") || mat.name().contains("EGG"))
            return Category.SPAWNERS;
        if (mat.name().contains("LOG") || mat.name().contains("PLANK") || mat.name().contains("WOOD")
                || mat.name().contains("STICK") || mat.name().contains("BAMBOO"))
            return Category.WOOD;
        if (mat.name().contains("COPPER") || mat.name().contains("RAW"))
            return Category.COPPER;
        if (mat.name().contains("REDSTONE") || mat.name().contains("REPEATER")
                || mat.name().contains("COMPARATOR") || mat.name().contains("PISTON"))
            return Category.REDSTONE;
        if (mat.name().contains("WOOL") || mat.name().contains("CONCRETE") || mat.name().contains("TERRACOTTA")
                || mat.name().contains("GLAZED") || mat.name().contains("STAINED"))
            return Category.COLORS;
        if (mat.name().contains("STONE") || mat.name().contains("BRICK") || mat.name().contains("COBBLESTONE")
                || mat.name().contains("SANDSTONE") || mat.name().contains("DEEPSLATE"))
            return Category.BUILDING;
        if (mat.name().contains("BANNER") || mat.name().contains("PAINTING") || mat.name().contains("ITEM_FRAME")
                || mat.name().contains("FLOWER") || mat.name().contains("POT"))
            return Category.DECORATION;

        return Category.CUSTOM_ITEMS;
    }

    /**
     * Resolve display name from Component displayName -> plain text, or fallback to formatted material name.
     */
    private String resolveDisplayName(ItemStack item) {
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() || meta.displayName() != null) {
                net.kyori.adventure.text.Component display = meta.displayName();
                if (display != null) {
                    return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                            .serialize(display);
                }
            }
        }
        return formatMaterialName(item.getType());
    }

    private String formatMaterialName(Material mat) {
        String name = mat.name().replace("_", " ").toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(" ");
        }
        return sb.toString().trim();
    }

    /**
     * Estimate a base price for a custom item based on its material.
     * Uses a simple heuristic: diamond/netherite = 500, iron/gold = 100, other = 50.
     */
    private BigDecimal estimatePrice(ItemStack item) {
        Material mat = item.getType();
        if (mat.name().contains("NETHERITE")) return BigDecimal.valueOf(500);
        if (mat.name().contains("DIAMOND")) return BigDecimal.valueOf(300);
        if (mat.name().contains("EMERALD")) return BigDecimal.valueOf(200);
        if (mat.name().contains("GOLD") || mat.name().contains("IRON")) return BigDecimal.valueOf(100);
        return BigDecimal.valueOf(50);
    }
}

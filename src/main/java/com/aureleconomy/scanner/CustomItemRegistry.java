package com.aureleconomy.scanner;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.database.DatabaseManager;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deduplication core. Maintains the canonical registry of all discovered
 * custom items and provides 5-way dedup lookup to prevent duplicate entries
 * even when the same item is found by multiple detection methods.
 */
public class CustomItemRegistry {

    private final AurelEconomy plugin;

    // Primary store: canonical ID -> item
    private final ConcurrentHashMap<String, CustomMarketItem> itemsById = new ConcurrentHashMap<>();

    // 5 dedup lookup maps
    private final ConcurrentHashMap<String, String> pdcKeyToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> modelDataToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> loreHashToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pluginNativeIdToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> itemHashToId = new ConcurrentHashMap<>();

    // Discovery tracking: which methods found each item
    private final ConcurrentHashMap<String, Set<DiscoveryMethod>> discoveryMethods = new ConcurrentHashMap<>();

    // Duplicate counter
    private final java.util.concurrent.atomic.AtomicLong duplicatesPrevented = new java.util.concurrent.atomic.AtomicLong(0);

    public CustomItemRegistry(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    /**
     * Register a custom item with deduplication. Returns a RegistrationResult
     * indicating whether the item was newly registered or was a duplicate.
     */
    public RegistrationResult register(CustomMarketItem item, DiscoveryMethod method) {
        String canonicalId = item.getCanonicalId();

        // 1. Check primary store
        if (itemsById.containsKey(canonicalId)) {
            addDiscoveryMethod(canonicalId, method);
            duplicatesPrevented.incrementAndGet();
            return RegistrationResult.alreadyExists(canonicalId, method);
        }

        // 2. Check PDC key
        if (item.getPdcKey() != null && pdcKeyToId.containsKey(item.getPdcKey())) {
            String existingId = pdcKeyToId.get(item.getPdcKey());
            addDiscoveryMethod(existingId, method);
            duplicatesPrevented.incrementAndGet();
            return RegistrationResult.alreadyExists(existingId, method);
        }

        // 3. Check model data key
        if (item.getModelDataKey() != null && modelDataToId.containsKey(item.getModelDataKey())) {
            String existingId = modelDataToId.get(item.getModelDataKey());
            addDiscoveryMethod(existingId, method);
            duplicatesPrevented.incrementAndGet();
            return RegistrationResult.alreadyExists(existingId, method);
        }

        // 4. Check lore hash
        if (item.getLoreHash() != null && loreHashToId.containsKey(item.getLoreHash())) {
            String existingId = loreHashToId.get(item.getLoreHash());
            addDiscoveryMethod(existingId, method);
            duplicatesPrevented.incrementAndGet();
            return RegistrationResult.alreadyExists(existingId, method);
        }

        // 5. Check plugin native ID
        if (item.getPluginNativeId() != null && pluginNativeIdToId.containsKey(item.getPluginNativeId())) {
            String existingId = pluginNativeIdToId.get(item.getPluginNativeId());
            addDiscoveryMethod(existingId, method);
            duplicatesPrevented.incrementAndGet();
            return RegistrationResult.alreadyExists(existingId, method);
        }

        // 6. Check item hash + isSimilar
        String itemHash = computeItemHash(item.getItemStack());
        if (itemHashToId.containsKey(itemHash)) {
            String existingId = itemHashToId.get(itemHash);
            CustomMarketItem existingItem = itemsById.get(existingId);
            if (existingItem != null && existingItem.getItemStack().isSimilar(item.getItemStack())) {
                addDiscoveryMethod(existingId, method);
                duplicatesPrevented.incrementAndGet();
                return RegistrationResult.alreadyExists(existingId, method);
            }
        }

        // NEW ITEM - insert into all stores
        itemsById.put(canonicalId, item);

        if (item.getPdcKey() != null) pdcKeyToId.put(item.getPdcKey(), canonicalId);
        if (item.getModelDataKey() != null) modelDataToId.put(item.getModelDataKey(), canonicalId);
        if (item.getLoreHash() != null) loreHashToId.put(item.getLoreHash(), canonicalId);
        if (item.getPluginNativeId() != null) pluginNativeIdToId.put(item.getPluginNativeId(), canonicalId);
        itemHashToId.put(itemHash, canonicalId);

        addDiscoveryMethod(canonicalId, method);

        // Add to market if auto-add is enabled and price is set
        if (plugin.getConfig().getBoolean("custom-items.auto-add-to-market", true)) {
            MarketManager marketManager = plugin.getMarketManager();
            if (marketManager != null) {
                marketManager.addCustomMarketItem(canonicalId, item);
            }
        }

        return RegistrationResult.newlyRegistered(canonicalId, method);
    }

    private void addDiscoveryMethod(String canonicalId, DiscoveryMethod method) {
        discoveryMethods.computeIfAbsent(canonicalId, k -> ConcurrentHashMap.newKeySet()).add(method);
    }

    /**
     * Compute a deep hash of an ItemStack for dedup purposes.
     * Hashes: Material, ItemMeta, CustomModelData, Lore, all PDC keys+values.
     */
    public String computeItemHash(ItemStack item) {
        if (item == null) return "null";
        int hash = item.getType().hashCode();
        if (item.hasItemMeta()) {
            hash = 31 * hash + item.getItemMeta().hashCode();
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            if (meta.hasCustomModelData()) {
                hash = 31 * hash + meta.getCustomModelData();
            }
            if (meta.hasLore() && meta.lore() != null) {
                hash = 31 * hash + meta.lore().hashCode();
            }
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                hash = 31 * hash + key.hashCode();
                if (pdc.has(key, PersistentDataType.STRING)) {
                    String value = pdc.get(key, PersistentDataType.STRING);
                    if (value != null) hash = 31 * hash + value.hashCode();
                }
            }
        }
        return String.valueOf(hash);
    }

    /**
     * Try to resolve a canonical ID from an ItemStack by checking all dedup keys.
     * Tries PDC first (most reliable), then model data, then lore hash, then item hash.
     */
    public Optional<String> resolveItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return Optional.empty();

        // PDC lookup (most reliable)
        if (item.hasItemMeta()) {
            PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
            for (NamespacedKey key : pdc.getKeys()) {
                String ns = key.getNamespace();
                if (!"minecraft".equals(ns) && !"aureleconomy".equals(ns)) {
                    String pdcKey = ns + ":" + key.getKey();
                    String id = pdcKeyToId.get(pdcKey);
                    if (id != null) return Optional.of(id);
                }
            }
        }

        // Model data lookup
        if (item.hasItemMeta() && item.getItemMeta().hasCustomModelData()) {
            String mdKey = item.getType().name() + ":" + item.getItemMeta().getCustomModelData();
            String id = modelDataToId.get(mdKey);
            if (id != null) return Optional.of(id);
        }

        // Lore hash lookup
        if (item.hasItemMeta() && item.getItemMeta().hasLore() && item.getItemMeta().lore() != null) {
            String lHash = String.valueOf(item.getItemMeta().lore().hashCode());
            String id = loreHashToId.get(lHash);
            if (id != null) return Optional.of(id);
        }

        // Item hash lookup
        String iHash = computeItemHash(item);
        String id = itemHashToId.get(iHash);
        if (id != null) {
            // Extra safety: confirm isSimilar
            CustomMarketItem existing = itemsById.get(id);
            if (existing != null && existing.getItemStack().isSimilar(item)) {
                return Optional.of(id);
            }
        }

        return Optional.empty();
    }

    /**
     * Load previously discovered items from database.
     */
    public void loadFromDatabase(DatabaseManager dbManager) {
        try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                "SELECT * FROM custom_items WHERE enabled = 1")) {
            ResultSet rs = ps.executeQuery();
            int loaded = 0;
            while (rs.next()) {
                String canonicalId = rs.getString("canonical_id");
                String sourcePlugin = rs.getString("source_plugin");
                String displayName = rs.getString("display_name");
                String itemData = rs.getString("item_data");
                String pdcKey = rs.getString("pdc_key");
                String modelDataKey = rs.getString("model_data_key");
                String loreHash = rs.getString("lore_hash");
                String pluginNativeId = rs.getString("plugin_native_id");
                String category = rs.getString("category");
                double buyPrice = rs.getDouble("buy_price");
                double sellPrice = rs.getDouble("sell_price");
                String methodsStr = rs.getString("discovery_methods");

                ItemStack itemStack;
                try {
                    itemStack = ItemStack.deserializeBytes(
                            org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.decodeLines(itemData));
                } catch (Exception e) {
                    plugin.getComponentLogger().warn("[CustomItems] Failed to deserialize item: " + canonicalId);
                    continue;
                }

                CustomMarketItem item = new CustomMarketItem.Builder()
                        .canonicalId(canonicalId)
                        .itemStack(itemStack)
                        .sourcePlugin(sourcePlugin)
                        .displayName(displayName != null ? displayName : itemStack.getType().name())
                        .pdcKey(pdcKey)
                        .modelDataKey(modelDataKey)
                        .loreHash(loreHash)
                        .pluginNativeId(pluginNativeId)
                        .category(category != null ? category : "CUSTOM_ITEMS")
                        .buyPrice(buyPrice >= 0 ? BigDecimal.valueOf(buyPrice) : BigDecimal.valueOf(-1))
                        .sellPrice(sellPrice >= 0 ? BigDecimal.valueOf(sellPrice) : BigDecimal.valueOf(-1))
                        .enabled(true)
                        .build();

                // Directly insert into maps (bypass register to avoid re-adding to market)
                itemsById.put(canonicalId, item);
                if (pdcKey != null) pdcKeyToId.put(pdcKey, canonicalId);
                if (modelDataKey != null) modelDataToId.put(modelDataKey, canonicalId);
                if (loreHash != null) loreHashToId.put(loreHash, canonicalId);
                if (pluginNativeId != null) pluginNativeIdToId.put(pluginNativeId, canonicalId);
                itemHashToId.put(computeItemHash(itemStack), canonicalId);

                // Restore discovery methods
                Set<DiscoveryMethod> methods = ConcurrentHashMap.newKeySet();
                if (methodsStr != null && !methodsStr.isEmpty()) {
                    for (String m : methodsStr.split(",")) {
                        try { methods.add(DiscoveryMethod.valueOf(m.trim())); } catch (IllegalArgumentException ignored) {}
                    }
                }
                discoveryMethods.put(canonicalId, methods);
                loaded++;
            }
            if (loaded > 0) {
                plugin.getComponentLogger().info("[CustomItems] Loaded " + loaded + " items from database.");
            }
        } catch (SQLException e) {
            plugin.getComponentLogger().error("[CustomItems] Failed to load custom items from database", e);
        }
    }

    /**
     * Save all discovered items to database asynchronously.
     */
    public void saveToDatabase(DatabaseManager dbManager) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (Map.Entry<String, CustomMarketItem> entry : itemsById.entrySet()) {
                try {
                    saveCustomItem(dbManager, entry.getKey(), entry.getValue(),
                            discoveryMethods.getOrDefault(entry.getKey(), Collections.emptySet()));
                } catch (SQLException e) {
                    plugin.getComponentLogger().error("[CustomItems] Failed to save item: " + entry.getKey(), e);
                }
            }
        });
    }

    private void saveCustomItem(DatabaseManager dbManager, String canonicalId, CustomMarketItem item,
                                 Set<DiscoveryMethod> methods) throws SQLException {
        String itemData = org.yaml.snakeyaml.external.biz.base64Coder.Base64Coder.encodeLines(
                item.getItemStack().serializeAsBytes());
        String methodsStr = methods.stream().map(DiscoveryMethod::name).reduce((a, b) -> a + "," + b).orElse("");

        if (dbManager.isMySQL()) {
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                    "INSERT INTO custom_items (canonical_id, source_plugin, display_name, item_data, pdc_key, " +
                    "model_data_key, lore_hash, plugin_native_id, category, buy_price, sell_price, enabled, " +
                    "discovery_methods, first_discovered, last_seen) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE display_name=?, item_data=?, pdc_key=?, model_data_key=?, " +
                    "lore_hash=?, plugin_native_id=?, category=?, buy_price=?, sell_price=?, enabled=?, " +
                    "discovery_methods=?, last_seen=?")) {
                long now = System.currentTimeMillis();
                long firstDiscovered = now; // will be overwritten by ON DUPLICATE KEY for existing
                populateInsertPS(ps, canonicalId, item, itemData, methodsStr, now);
                ps.setLong(15, now);
                // ON DUPLICATE KEY UPDATE params (indices 16+)
                ps.setString(16, item.getDisplayName());
                ps.setString(17, itemData);
                ps.setString(18, item.getPdcKey());
                ps.setString(19, item.getModelDataKey());
                ps.setString(20, item.getLoreHash());
                ps.setString(21, item.getPluginNativeId());
                ps.setString(22, item.getCategory());
                ps.setDouble(23, item.getBuyPrice().doubleValue());
                ps.setDouble(24, item.getSellPrice().doubleValue());
                ps.setBoolean(25, item.isEnabled());
                ps.setString(26, methodsStr);
                ps.setLong(27, now);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                    "INSERT OR REPLACE INTO custom_items (canonical_id, source_plugin, display_name, item_data, " +
                    "pdc_key, model_data_key, lore_hash, plugin_native_id, category, buy_price, sell_price, " +
                    "enabled, discovery_methods, first_discovered, last_seen) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                long now = System.currentTimeMillis();
                // For SQLite INSERT OR REPLACE, preserve first_discovered if item exists
                long firstDiscovered = now;
                try (PreparedStatement lookup = dbManager.getConnection().prepareStatement(
                        "SELECT first_discovered FROM custom_items WHERE canonical_id = ?")) {
                    lookup.setString(1, canonicalId);
                    ResultSet rs = lookup.executeQuery();
                    if (rs.next()) firstDiscovered = rs.getLong("first_discovered");
                }
                populateInsertPS(ps, canonicalId, item, itemData, methodsStr, firstDiscovered);
                ps.setLong(15, now);
                ps.executeUpdate();
            }
        }
    }

    private void populateInsertPS(PreparedStatement ps, String canonicalId, CustomMarketItem item,
                                   String itemData, String methodsStr, long firstDiscovered) throws SQLException {
        ps.setString(1, canonicalId);
        ps.setString(2, item.getSourcePlugin());
        ps.setString(3, item.getDisplayName());
        ps.setString(4, itemData);
        ps.setString(5, item.getPdcKey());
        ps.setString(6, item.getModelDataKey());
        ps.setString(7, item.getLoreHash());
        ps.setString(8, item.getPluginNativeId());
        ps.setString(9, item.getCategory());
        ps.setDouble(10, item.getBuyPrice().doubleValue());
        ps.setDouble(11, item.getSellPrice().doubleValue());
        ps.setBoolean(12, item.isEnabled());
        ps.setString(13, methodsStr);
        ps.setLong(14, firstDiscovered);
    }

    /**
     * Delete a custom item from registry and database.
     */
    public void deleteCustomItem(DatabaseManager dbManager, String canonicalId) {
        CustomMarketItem removed = itemsById.remove(canonicalId);
        if (removed != null) {
            if (removed.getPdcKey() != null) pdcKeyToId.remove(removed.getPdcKey());
            if (removed.getModelDataKey() != null) modelDataToId.remove(removed.getModelDataKey());
            if (removed.getLoreHash() != null) loreHashToId.remove(removed.getLoreHash());
            if (removed.getPluginNativeId() != null) pluginNativeIdToId.remove(removed.getPluginNativeId());
            itemHashToId.remove(computeItemHash(removed.getItemStack()));
            discoveryMethods.remove(canonicalId);
        }
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                    "DELETE FROM custom_items WHERE canonical_id = ?")) {
                ps.setString(1, canonicalId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("[CustomItems] Failed to delete item: " + canonicalId, e);
            }
        });
    }

    /**
     * Update prices for a custom item in the database.
     */
    public void updateCustomItemPrice(DatabaseManager dbManager, String canonicalId, double buyPrice, double sellPrice) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = dbManager.getConnection().prepareStatement(
                    "UPDATE custom_items SET buy_price = ?, sell_price = ? WHERE canonical_id = ?")) {
                ps.setDouble(1, buyPrice);
                ps.setDouble(2, sellPrice);
                ps.setString(3, canonicalId);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getComponentLogger().error("[CustomItems] Failed to update price for: " + canonicalId, e);
            }
        });
    }

    // Query methods
    public Collection<CustomMarketItem> getAllItems() { return Collections.unmodifiableCollection(itemsById.values()); }
    public CustomMarketItem getById(String canonicalId) { return itemsById.get(canonicalId); }
    public Set<DiscoveryMethod> getDiscoveryMethods(String canonicalId) {
        return Collections.unmodifiableSet(discoveryMethods.getOrDefault(canonicalId, Collections.emptySet()));
    }
    public int getTotalItems() { return itemsById.size(); }
    public long getDuplicatesPrevented() { return duplicatesPrevented.get(); }
    public boolean isEmpty() { return itemsById.isEmpty(); }
  /**
  * Sync discovered items to config.yml so admins can see and configure them.
  * Called after each scan cycle. Only writes items not already in config.
  */
  public void syncToConfig() {
  org.bukkit.configuration.file.YamlConfiguration config = (org.bukkit.configuration.file.YamlConfiguration) plugin.getConfig();
  boolean changed = false;

  for (java.util.Map.Entry<String, CustomMarketItem> entry : itemsById.entrySet()) {
  String canonicalId = entry.getKey();
  CustomMarketItem item = entry.getValue();
  String path = "discovered-items." + canonicalId;

  // Only add if not already in config (admin may have customized it)
  if (!config.contains(path)) {
  config.set(path + ".source-plugin", item.getSourcePlugin());
  config.set(path + ".display-name", item.getDisplayName());
  config.set(path + ".category", item.getCategory());
  config.set(path + ".buy-price", item.getBuyPrice().doubleValue());
  config.set(path + ".sell-price", item.getSellPrice().doubleValue());
  config.set(path + ".enabled", item.isEnabled());
  if (item.getPdcKey() != null) config.set(path + ".pdc-key", item.getPdcKey());
  if (item.getModelDataKey() != null) config.set(path + ".model-data-key", item.getModelDataKey());
  if (item.getPluginNativeId() != null) config.set(path + ".plugin-native-id", item.getPluginNativeId());
  changed = true;
  }
  }

  if (changed) {
  plugin.saveConfig();
  plugin.getComponentLogger().info("[CustomItems] Config updated with newly discovered items.");
  }
  }

  /**
  * Load discovered item overrides from config.yml.
  * Admins can customize prices, enabled status, etc. in config.
  * These override database values on startup.
  */
  public void loadConfigOverrides() {
  org.bukkit.configuration.ConfigurationSection section = plugin.getConfig().getConfigurationSection("discovered-items");
  if (section == null) return;

  for (String canonicalId : section.getKeys(false)) {
  if (!itemsById.containsKey(canonicalId)) continue;
  CustomMarketItem existing = itemsById.get(canonicalId);
  String path = "discovered-items." + canonicalId;

  CustomMarketItem.Builder builder = new CustomMarketItem.Builder()
  .canonicalId(canonicalId)
  .itemStack(existing.getItemStack())
  .sourcePlugin(section.getString(path + ".source-plugin", existing.getSourcePlugin()))
  .displayName(section.getString(path + ".display-name", existing.getDisplayName()))
  .category(section.getString(path + ".category", existing.getCategory()))
  .enabled(section.getBoolean(path + ".enabled", existing.isEnabled()));

  double buyPrice = section.getDouble(path + ".buy-price", existing.getBuyPrice().doubleValue());
  double sellPrice = section.getDouble(path + ".sell-price", existing.getSellPrice().doubleValue());
  builder.buyPrice(java.math.BigDecimal.valueOf(buyPrice));
  builder.sellPrice(java.math.BigDecimal.valueOf(sellPrice));

  String pdcKey = section.getString(path + ".pdc-key");
  if (pdcKey != null) builder.pdcKey(pdcKey);
  String modelDataKey = section.getString(path + ".model-data-key");
  if (modelDataKey != null) builder.modelDataKey(modelDataKey);
  String pluginNativeId = section.getString(path + ".plugin-native-id");
  if (pluginNativeId != null) builder.pluginNativeId(pluginNativeId);

  itemsById.put(canonicalId, builder.build());
  }
  }

}
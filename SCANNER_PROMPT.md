# Implementation Prompt: Custom Item Scanning & Deduplication System for Aurelium

**Target Repository**: https://github.com/NanoBotAgent/Aurelium/tree/fix/mysql-compat-and-auction-displayname  
**Package Root**: `com.aureleconomy`  
**Plugin Version**: 1.4.5

---

## ⚠️ DUAL-TARGET IMPLEMENTATION — READ THIS FIRST

This feature must be implemented for **TWO separate branches/targets** in the repository. The code logic is identical, but the Paper API version, build configuration, and some API details differ. You MUST produce working code for **both** targets.

### Target A: 26.1.x Branch
| Property | Value |
|----------|-------|
| Paper API | `io.papermc.paper:paper-api:26.1.2-build-64` |
| `api-version` in plugin.yml | `'26.1'` |
| Java | 25 |
| Build system | `build.gradle.kts` (active) |
| ItemStack serialization | `item.serializeAsBytes()` / `ItemStack.deserializeBytes()` (Paper native) |
| Component API | Adventure `Component` (net.kyori.adventure) bundled with Paper |
| Registry access | `RegistryAccess` via `Bukkit.getRegistry()` or `Bukkit.getServer().getRegistry()` |
| `plugin.yml` | Traditional Bukkit plugin.yml (no paper-plugin.yml) |

### Target B: 1.21.x Branch
| Property | Value |
|----------|-------|
| Paper API | `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT` |
| `api-version` in plugin.yml | `'1.21'` |
| Java | 21 |
| Build system | `pom.xml` (Maven, shaded) |
| ItemStack serialization | `item.serializeAsBytes()` / `ItemStack.deserializeBytes()` (Paper native, available since 1.20.5+) |
| Component API | Adventure `Component` (net.kyori.adventure) bundled with Paper |
| Registry access | `RegistryAccess` via `Bukkit.getRegistry()` or `Bukkit.getServer().getRegistry()` |
| `plugin.yml` | Traditional Bukkit plugin.yml (no paper-plugin.yml) |

### Key API Differences Between Targets

| Area | 26.1.x (Target A) | 1.21.x (Target B) |
|------|-------------------|-------------------|
| Paper API coordinate | `paper-api:26.1.2-build-64` | `paper-api:1.21.11-R0.1-SNAPSHOT` |
| Gradle/Maven | Gradle (`build.gradle.kts`) | Maven (`pom.xml`) |
| Java toolchain | 25 | 21 |
| `api-version` | `'26.1'` | `'1.21'` |
| Winter Drop items (Pale Oak, etc.) | May have materials not in 1.21.11 | Uses 1.21.11 material set only |
| `CustomModelData` | Same API | Same API |
| `PersistentDataContainer` | Same API | Same API |
| `serializeAsBytes()` | Same API | Same API |
| Adventure `Component` | Same API | Same API |

**Implementation strategy**: Write the scanner code to be **API-version agnostic** — the PDC, CustomModelData, Lore, Inventory, and Interaction detection methods use identical APIs on both targets. The ONLY differences are:
1. The `build.gradle.kts` or `pom.xml` dependency coordinate
2. The `api-version` in `plugin.yml`
3. The Java toolchain version
4. Any material names that differ between MC versions (handle with `Material.matchMaterial()` + null checks)

---

## Context

Aurelium is an economy plugin with a market, auction house, and web dashboard. Currently, the market system uses a **hardcoded static catalog** in `MarketItems.java` — a massive `enum`-based registry with hundreds of vanilla items defined in a `static {}` block. There is **no mechanism to discover or register custom items added by other plugins** (ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item, etc.).

The goal is to add a **unified custom item scanning system** that:
1. Discovers custom items from other plugins using **6 complementary detection methods**
2. **Guarantees no duplicates** — even when the same item is found by multiple methods
3. Integrates discovered items into the existing market and auction systems
4. Persists custom item data to the database (both SQLite and MySQL)
5. Auto-rescans when custom item plugins reload
6. Works gracefully when no custom item plugins are installed

---

## Existing Architecture You Must Integrate With

### Main Plugin Class (`AurelEconomy.java`)
- Extends `JavaPlugin`
- Initializes managers in `onEnable()`: `DatabaseManager`, `EconomyManager`, `MarketManager`, `AuctionManager`, `ChatPromptManager`, `OrderManager`, `VaultEconomy`, `WebServer`, `CloudSyncManager`
- Registers listeners: `GUIListener`, `SpawnerListener`, `JoinListener`
- Scheduled tasks: GUI update (20-tick), market price persistence (6000-tick), auction expiry (1200-tick)
- Tracks active GUI viewers in `ConcurrentHashMap<UUID, Boolean>`

### Market System (`MarketManager.java` + `MarketItems.java`)
- `MarketItems` has a `Category` enum with 14 categories: `TOOLS_WEAPONS`, `FOOD_FARMING`, `MINERALS_ORES`, `MOB_DROPS`, `NATURE`, `REDSTONE`, `WOOD`, `COPPER`, `SPAWNERS`, `COLORS`, `BUILDING`, `DECORATION`, `ENCHANTMENTS`, `ALL_ITEMS`
- Each `MarketEntry` contains: `Material`, `BigDecimal price`, `BigDecimal customSellPrice`, `String customName`, `String searchName`
- `MarketManager` handles dynamic pricing (0.1% increase per buy, 0.1% decrease per sell), price floor/ceiling (20%-500% of base), price recovery (1% per 10min), crash alerts, and a blacklist
- Prices persisted to `config.yml` under `market-items.*` keys
- Key methods: `getBuyPrice(String itemKey)`, `getSellPrice(String itemKey)`, `addMarketItem()`, `removeMarketItem()`

### Auction System (`AuctionManager.java` + `AuctionItem.java`)
- `AuctionItem` uses a Builder pattern with fields: `id`, `seller` (UUID), `item` (ItemStack), `price` (BigDecimal), `currency` (String), `isBin` (boolean), `expiration` (long), `listingFee` (BigDecimal), `startTime` (long), `highestBidder` (UUID), `ended` (boolean), `collected` (boolean)
- Items serialized via `item.serializeAsBytes()` → `Base64Coder.encodeLines()` → stored as TEXT in `auctions.item_data`
- Display names resolved via `getItemDisplayName()`: checks `meta.displayName()` (Component), falls back to `Material.name()`
- Atomic DB operations for bidding/purchasing to prevent duplication

### Database (`DatabaseManager.java`)
- Dual support: SQLite (default) and MySQL (configured via `database.type` in config.yml)
- `isMySQL()` helper used for SQL dialect differences (AUTO_INCREMENT vs AUTOINCREMENT, ON DUPLICATE KEY vs ON CONFLICT)
- Single `Connection` with auto-reconnect
- Tables: `database_info`, `players`, `player_balances`, `auctions`, `auction_offers`, `offline_earnings`, `buy_orders`, `price_history`
- Schema versioning system: `LATEST_SCHEMA_VERSION = 1`, migrations applied on startup

### Build System

**Target A (26.1.x) — `build.gradle.kts`:**
```kotlin
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2-build-64")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { exclude("org.bukkit", "bukkit") }
}
repositories: mavenCentral(), repo.papermc.io, jitpack.io
java.toolchain.languageVersion = JavaLanguageVersion.of(25)
```

**Target B (1.21.x) — `pom.xml`:**
```xml
<dependency>
    <groupId>io.papermc.paper</groupId>
    <artifactId>paper-api</artifactId>
    <version>1.21.11-R0.1-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
<dependency>
    <groupId>com.github.MilkBowl</groupId>
    <artifactId>VaultAPI</artifactId>
    <version>1.7</version>
    <scope>provided</scope>
</dependency>
java.version: 21
```

### Plugin Descriptor (`plugin.yml`)

**Target A (26.1.x):**
```yaml
name: Aurelium
version: '1.4.5'
main: com.aureleconomy.AurelEconomy
api-version: '26.1'
softdepend: [Vault]
libraries: [org.xerial:sqlite-jdbc:3.45.3.0]
```

**Target B (1.21.x):**
```yaml
name: Aurelium
version: '1.4.5'
main: com.aureleconomy.AurelEconomy
api-version: '1.21'
softdepend: [Vault]
libraries: [org.xerial:sqlite-jdbc:3.45.3.0]
```

---

## What to Implement

### 1. New Package: `com.aureleconomy.scanner`

Create these new classes inside this package:

#### `DiscoveryMethod.java` — Enum
```java
public enum DiscoveryMethod {
    PLUGIN_API_ITEMSADDER("ItemsAdder API"),
    PLUGIN_API_ORAXEN("Oraxen API"),
    PLUGIN_API_MMOITEMS("MMOItems API"),
    PLUGIN_API_MYTHICMOBS("MythicMobs API"),
    PLUGIN_API_EXECUTABLE_ITEMS("ExecutableItems API"),
    PLUGIN_API_NEXO("Nexo API"),
    PLUGIN_API_SX_ITEM("SX-Item API"),
    PDC_SCAN("PDC Scan"),
    CUSTOM_MODEL_DATA("CustomModelData"),
    LORE_PATTERN("Lore Pattern"),
    INVENTORY_SCAN("Inventory Scan"),
    INTERACTION_DETECT("Player Interaction");

    private final String displayName;
    // constructor + getter
}
```

#### `CustomMarketItem.java` — Data Model (Builder Pattern)
Must contain:
- `String canonicalId` — The unique identifier for this item across all detection methods (e.g., `"itemsadder:ruby_sword"`)
- `ItemStack itemStack` — A **cloned** copy of the item
- `String sourcePlugin` — Human-readable plugin name
- `String displayName` — Resolved from `meta.displayName()` Component → plain text, or fallback to formatted material name
- **Dedup keys** (nullable, set by whichever method detected the item):
  - `String pdcKey` — e.g., `"itemsadder:ruby_sword"` from PersistentDataContainer namespace:key
  - `String modelDataKey` — e.g., `"DIAMOND_SWORD:10001"` from material + custom model data
  - `String loreHash` — `String.valueOf(lore.hashCode())` from item lore
  - `String pluginNativeId` — The ID from the plugin's own API (e.g., `"itemsadder:ruby_sword"` or `"oraxen:custom_axe"`)
- `String category` — Auto-assigned based on item material or manually set
- `BigDecimal buyPrice` — Default -1 (unset)
- `BigDecimal sellPrice` — Default -1 (unset)
- `boolean enabled` — Default true
- Use a Builder pattern consistent with `AuctionItem`'s builder style

#### `RegistrationResult.java` — Result Record
```java
public class RegistrationResult {
    private final String canonicalId;
    private final boolean isNew;       // true = newly registered, false = duplicate prevented
    private final DiscoveryMethod method;

    public static RegistrationResult newlyRegistered(String id, DiscoveryMethod method) { ... }
    public static RegistrationResult alreadyExists(String id, DiscoveryMethod method) { ... }
    // getters
}
```

#### `CustomItemRegistry.java` — The Deduplication Core
This is the most critical class. It must:

1. **Primary store**: `ConcurrentHashMap<String, CustomMarketItem> itemsById` — canonical ID → item
2. **5 dedup lookup maps** (all `ConcurrentHashMap`):
   - `pdcKeyToId` — PDC key string → canonical ID
   - `modelDataToId` — "MAT:123" → canonical ID
   - `loreHashToId` — lore hash → canonical ID
   - `pluginNativeIdToId` — plugin's native ID → canonical ID
   - `itemHashToId` — deep ItemStack hash → canonical ID
3. **Discovery tracking**: `ConcurrentHashMap<String, Set<DiscoveryMethod>>` — which methods found each item
4. **Duplicate counter**: Track how many duplicate registrations were prevented

**`register(CustomMarketItem item, DiscoveryMethod method)` logic:**
```
1. Check: itemsById already contains canonicalId? → DUPLICATE, just add method to discoveryMethods set
2. Check: pdcKey exists in pdcKeyToId? → DUPLICATE, add method
3. Check: modelDataKey exists in modelDataToId? → DUPLICATE, add method
4. Check: loreHash exists in loreHashToId? → DUPLICATE, add method
5. Check: pluginNativeId exists in pluginNativeIdToId? → DUPLICATE, add method
6. Check: computeItemHash(item) exists in itemHashToId? → Extra safety: also call itemStack.isSimilar(existing) → DUPLICATE if match
7. If none matched: NEW ITEM → insert into itemsById + all dedup maps + discoveryMethods, call MarketManager.addMarketItem()
```

**`computeItemHash(ItemStack)` method:**
- Hash based on: `Material.hashCode()`, `ItemMeta.hashCode()`, `CustomModelData`, `Lore.hashCode()`, and all PDC keys + string values
- Use `PersistentDataType.STRING` to read PDC values for hashing

**`resolveItemId(ItemStack)` method:**
- Tries PDC keys first (most reliable), then model data key, then lore hash, then item hash
- Returns `Optional<String>` of canonical ID — used by auction/market to look up custom items

**Other methods**: `getAllItems()`, `getById()`, `getDiscoveryMethods()`, `getTotalItems()`, `getDuplicatesPrevented()`

#### `UnifiedItemScanner.java` — All 6 Detection Methods

**METHOD 1: Plugin-Specific API Scanning**
- `scanAllPluginAPIs()` → calls `scanItemsAdder()`, `scanOraxen()`, `scanMMOItems()`, `scanMythicMobs()`, `scanExecutableItems()`, `scanNexo()`, `scanSXItem()`
- Each method:
  1. Check `Bukkit.getPluginManager().getPlugin("PluginName") == null` → skip
  2. Try-catch around the plugin's API call (use `NoClassDefFoundError` too)
  3. Iterate all items from the plugin's API
  4. Build a `CustomMarketItem` with ALL dedup keys populated (`pdcKey`, `modelDataKey`, `loreHash`, `pluginNativeId`)
  5. Call `registry.register(item, DiscoveryMethod.PLUGIN_API_*)`
- **IMPORTANT**: Since these are compileOnly dependencies that won't be available at compile time, you must use **reflection** to call the APIs. Create a helper method `safeCall(String pluginName, Runnable apiCall)` or use reflection-based invocation. Alternatively, use `Class.forName()` checks. The code must compile WITHOUT these plugins on the classpath.

**METHOD 2: PersistentDataContainer Scan**
- `scanViaPDC(ItemStack item)`:
  1. Check item has meta + PDC has keys
  2. Iterate `pdc.getKeys()`
  3. Skip namespaces `"minecraft"` and `"aureleconomy"` (our own)
  4. Any other namespace = custom item
  5. Build CustomMarketItem with `pdcKey = namespace + ":" + key`
  6. Generate canonicalId as `namespace + ":" + material.name().toLowerCase() + "_" + key`
  7. Register with `DiscoveryMethod.PDC_SCAN`

**METHOD 3: CustomModelData Scan**
- `scanViaModelData(ItemStack item)`:
  1. Check `meta.hasCustomModelData()` and `getCustomModelData() > 0`
  2. Build key as `material.name() + ":" + modelData`
  3. canonicalId = `"modeldata:" + key.toLowerCase()`
  4. Register with `DiscoveryMethod.CUSTOM_MODEL_DATA`

**METHOD 4: Lore Pattern Scan**
- `scanViaLore(ItemStack item)`:
  1. Check has lore
  2. Scan for hex color patterns (`§x`), common identifiers (`CustomItem:`, `ItemsAdder`), or hex color sequences
  3. If custom detected: compute `loreHash = String.valueOf(lore.hashCode())`
  4. canonicalId = `"lore:" + material.name().toLowerCase() + "_" + loreHash`
  5. Register with `DiscoveryMethod.LORE_PATTERN`

**METHOD 5: Player Inventory Scan**
- `scanPlayerInventories()`:
  1. Iterate all online players
  2. Check `player.getInventory().getContents()` and `player.getEnderChest().getContents()`
  3. Call `scanSingleItem(item, DiscoveryMethod.INVENTORY_SCAN)` for each

**METHOD 6: Player Interaction Detection** (via listeners, see below)
- `scanSingleItem(ItemStack item, DiscoveryMethod method)`:
  1. Null/air check
  2. Call all 3 passive methods on it: `scanViaPDC()`, `scanViaModelData()`, `scanViaLore()`
  3. Also try `scanItemAgainstPluginAPIs(item)` — uses each plugin's `byItemStack()`-style API to check if the item is from that plugin

**Utility methods**:
- `extractPdcKey(ItemStack)` → first non-minecraft, non-aureleconomy PDC key as `"namespace:key"`, or null
- `extractModelDataKey(ItemStack)` → `"MATERIAL:12345"` or null
- `extractLoreHash(ItemStack)` → lore hash string or null
- `detectPluginFromNamespace(String)` → map common namespaces to plugin names
- `autoAssignCategory(ItemStack)` → infer a `Category` from material type

#### `ItemDiscoveryListener.java` — Runtime Detection Events
Register these Bukkit event listeners (all `EventPriority.MONITOR`, `ignoreCancelled = true`):
- `PlayerInteractEvent` → scan `event.getItem()`
- `InventoryClickEvent` → scan `event.getCurrentItem()` and `event.getCursor()`
- `EntityPickupItemEvent` → scan `event.getItem().getItemStack()`
- `CraftItemEvent` → scan `event.getRecipe().getResult()`
- `InventoryOpenEvent` → scan all items in the opened inventory

All delegate to `scanner.scanSingleItem(item, DiscoveryMethod.INTERACTION_DETECT)`.

---

### 2. Database Schema Changes (`DatabaseManager.java`)

Add a new table `custom_items` with proper SQLite/MySQL dialect handling:

**MySQL:**
```sql
CREATE TABLE IF NOT EXISTS custom_items (
    canonical_id VARCHAR(255) PRIMARY KEY,
    source_plugin VARCHAR(64) NOT NULL,
    display_name VARCHAR(256),
    item_data TEXT NOT NULL,
    pdc_key VARCHAR(255),
    model_data_key VARCHAR(128),
    lore_hash VARCHAR(64),
    plugin_native_id VARCHAR(255),
    category VARCHAR(64),
    buy_price DOUBLE DEFAULT -1,
    sell_price DOUBLE DEFAULT -1,
    enabled TINYINT(1) DEFAULT 1,
    discovery_methods VARCHAR(256),
    first_discovered BIGINT NOT NULL,
    last_seen BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
```

**SQLite:**
```sql
CREATE TABLE IF NOT EXISTS custom_items (
    canonical_id TEXT PRIMARY KEY,
    source_plugin TEXT NOT NULL,
    display_name TEXT,
    item_data TEXT NOT NULL,
    pdc_key TEXT,
    model_data_key TEXT,
    lore_hash TEXT,
    plugin_native_id TEXT,
    category TEXT,
    buy_price REAL DEFAULT -1,
    sell_price REAL DEFAULT -1,
    enabled INTEGER DEFAULT 1,
    discovery_methods TEXT,
    first_discovered INTEGER NOT NULL,
    last_seen INTEGER NOT NULL
)
```

Increment `LATEST_SCHEMA_VERSION` to 2 and add a migration that creates this table if it doesn't exist.

Add methods to `DatabaseManager`:
- `saveCustomItem(CustomMarketItem item, Set<DiscoveryMethod> methods)` — INSERT or UPDATE (use proper MySQL `ON DUPLICATE KEY UPDATE` vs SQLite `INSERT OR REPLACE`)
- `loadCustomItems()` → `Map<String, CustomMarketItem>` — load all on startup
- `deleteCustomItem(String canonicalId)` — remove from DB
- `updateCustomItemPrice(String canonicalId, double buyPrice, double sellPrice)` — price updates

Use the existing `isMySQL()` pattern for dialect differences. Use async database calls consistent with the rest of the codebase.

---

### 3. Integration with Main Plugin (`AurelEconomy.java`)

In `onEnable()`, after existing manager initialization:

```java
// Initialize custom item system
this.customItemRegistry = new CustomItemRegistry(this);
this.unifiedScanner = new UnifiedItemScanner(this, customItemRegistry);

// Phase 1: Load previously discovered items from database
customItemRegistry.loadFromDatabase(databaseManager);

// Phase 2: Plugin API scan (delayed 1 second to ensure other plugins are fully loaded)
Bukkit.getScheduler().runTaskLater(this, () -> {
    unifiedScanner.scanAllPluginAPIs();
    unifiedScanner.scanPlayerInventories();
    getLogger().info("[CustomItems] Scan complete: " + customItemRegistry.getTotalItems() + 
                     " unique items, " + customItemRegistry.getDuplicatesPrevented() + " duplicates prevented");
}, 20L);

// Phase 3: Register runtime detection listeners
getServer().getPluginManager().registerEvents(new ItemDiscoveryListener(unifiedScanner), this);

// Phase 4: Periodic rescan (every 10 minutes)
new BukkitRunnable() {
    @Override
    public void run() {
        unifiedScanner.scanPlayerInventories();
        customItemRegistry.saveToDatabase(databaseManager);
    }
}.runTaskTimer(this, 20L * 60 * 10, 20L * 60 * 10);
```

Add a `PluginReloadListener` inner class that listens for:
- ItemsAdder's `ItemsAdderLoadEvent` (via reflection since it's soft-depend)
- `ServerLoadEvent` (Bukkit native) for `/reload` support
- When triggered: rescan via `scanAllPluginAPIs()` + `scanPlayerInventories()`

In `onDisable()`:
- Call `customItemRegistry.saveToDatabase(databaseManager)` to persist all discovered items

---

### 4. Integration with Market System (`MarketManager.java`)

Add a new category to `MarketItems.Category` enum: `CUSTOM_ITEMS`

Add method to `MarketManager`:
```java
public void addCustomMarketItem(String canonicalId, CustomMarketItem customItem) {
    // Use the custom item's display name and pricing
    // Add to the buy/sell price maps
    // Assign to CUSTOM_ITEMS category
    // Save to database
}
```

Modify `MarketGUI` and `ShopGUI` to include the `CUSTOM_ITEMS` category tab. When selected, show items from `CustomItemRegistry.getAllItems()` that have `enabled = true`.

Add a `MarketItems.getCustomEntries()` method that returns entries from the registry for search/filter purposes.

---

### 5. Integration with Auction System (`AuctionManager.java`)

Modify `getItemDisplayName(ItemStack item)`:
1. First, check if the item matches any registered custom item via `customItemRegistry.resolveItemId(item)`
2. If found, use the `CustomMarketItem.getDisplayName()` from the registry
3. Otherwise, fall back to the existing logic (Component displayName → Material name)

This ensures custom items show their proper names in the auction house.

---

### 6. Update `plugin.yml`

Add all custom item plugins as soft dependencies:
```yaml
softdepend: [Vault, ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item]
```

---

### 7. Update `config.yml`

Add a new section:
```yaml
custom-items:
  enabled: true
  scan-on-startup: true
  scan-interval-minutes: 10
  auto-add-to-market: true
  default-price-multiplier: 1.5    # buy = base * multiplier, sell = base
  discovery-methods:
    plugin-api: true               # Use plugin-specific APIs
    pdc-scan: true                 # Scan PersistentDataContainer
    custom-model-data: true        # Detect custom model data
    lore-pattern: true             # Scan lore for custom item markers
    inventory-scan: true           # Scan player inventories
    interaction-detect: true       # Detect on player interaction
  excluded-namespaces:             # PDC namespaces to ignore
    - minecraft
    - aureleconomy
  category-mapping:                # Auto-assign categories by material
    TOOLS_WEAPONS: [WOODEN_SWORD, STONE_SWORD, IRON_SWORD, GOLDEN_SWORD, DIAMOND_SWORD, NETHERITE_SWORD, ...]
    FOOD_FARMING: [WHEAT, CARROT, POTATO, ...]
    # ... etc
```

---

### 8. New Command: `/customitems` (Admin)

Add to `AurelEconomy.java` command registration:
```
/customitems scan       — Trigger an immediate full rescan
/customitems list       — List all discovered custom items
/customitems info <id>  — Show details about a custom item (discovery methods, source, etc.)
/customitems reload     — Reload custom items from database + rescan
/customitems toggle <id> — Enable/disable a custom item in the market
/customitems price <id> <buy> <sell> — Set custom pricing
```

Create `CustomItemsCommand.java` in `commands/` package implementing `TabExecutor`.

---

### 9. Add Admin GUI: `CustomItemsGUI.java`

Add to `gui/` package:
- Shows all discovered custom items in a paginated GUI
- Click an item to see details: source plugin, discovery methods, canonical ID, prices
- Toggle button to enable/disable in market
- Edit price button → uses `ChatPromptManager` for price input (consistent with existing sell flow)

---

## Critical Implementation Notes

1. **Reflection for soft-depend APIs**: Since ItemsAdder, Oraxen, MMOItems, etc. are soft dependencies and NOT on the compile classpath, you MUST use reflection to call their APIs. Pattern:
   ```java
   private void scanItemsAdder() {
       Plugin plugin = Bukkit.getPluginManager().getPlugin("ItemsAdder");
       if (plugin == null) return;
       try {
           Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
           Method getItemsMethod = customStackClass.getMethod("getItems");
           Method getItemStackMethod = customStackClass.getMethod("getItemStack");
           Method getNamespacedIDMethod = customStackClass.getMethod("getNamespacedID");
           // invoke via reflection
       } catch (ClassNotFoundException | NoClassDefFoundError e) {
           // Plugin not present, skip
       } catch (Exception e) {
           getLogger().warning("[Scanner] ItemsAdder scan failed: " + e.getMessage());
       }
   }
   ```

2. **Thread safety**: Use `ConcurrentHashMap` for all registry maps. DB operations should be async (use `Bukkit.getScheduler().runTaskAsynchronously()`). Item manipulation must be on the main thread.

3. **Paper API compatibility (both targets)**: Use `item.serializeAsBytes()` / `ItemStack.deserializeBytes()` for serialization (available on both Paper 26.1.2-build-64 and Paper 1.21.11-R0.1-SNAPSHOT). Use Adventure `Component` API for display names, NOT legacy `ChatColor`. The scanner code must be **API-version agnostic** — all PDC, CustomModelData, Lore, Inventory, and Interaction APIs are identical between targets. The ONLY per-target differences are the build dependency coordinate and `api-version` in `plugin.yml`. Handle material differences with `Material.matchMaterial(name)` + null checks (e.g., Pale Oak items may not exist on 1.21.x).

4. **MySQL/SQLite compatibility**: Every SQL statement must check `isMySQL()` and use the correct dialect. Follow the exact pattern used in `DatabaseManager.java`.

5. **BigDecimal for prices**: All price calculations must use `BigDecimal` for precision, consistent with the existing economy system.

6. **ItemStack cloning**: Always store `item.clone()` in the registry to prevent mutation bugs.

7. **Graceful degradation**: If a custom item plugin fails to scan, log a warning and continue with other methods. The system must work perfectly with zero custom item plugins installed.

8. **No new compile dependencies**: Do NOT add ItemsAdder, Oraxen, MMOItems, etc. to `build.gradle.kts` OR `pom.xml`. All access via reflection only. This applies to BOTH target branches.

9. **Consistent logging**: Use the same logging format as existing code: `getLogger().info("[CustomItems] ...")`

10. **Schema versioning**: Bump `LATEST_SCHEMA_VERSION` to 2 and create a proper migration. Do NOT modify existing tables.

11. **GUI consistency**: New GUIs must extend `GUIHolder` and follow the same pattern as `AuctionGUI`, `ShopGUI`, etc. Use `ItemBuilder` for creating display items. Use MiniMessage for component formatting.

12. **Config backward compatibility**: Add the `custom-items` section with defaults. Existing config files must still work after update.

---

## File Checklist (New Files) — Same for BOTH targets

| File | Package |
|------|---------|
| `DiscoveryMethod.java` | `com.aureleconomy.scanner` |
| `CustomMarketItem.java` | `com.aureleconomy.scanner` |
| `RegistrationResult.java` | `com.aureleconomy.scanner` |
| `CustomItemRegistry.java` | `com.aureleconomy.scanner` |
| `UnifiedItemScanner.java` | `com.aureleconomy.scanner` |
| `ItemDiscoveryListener.java` | `com.aureleconomy.scanner` |
| `CustomItemsCommand.java` | `com.aureleconomy.commands` |
| `CustomItemsGUI.java` | `com.aureleconomy.gui` |

## Files to Modify — Per Target

### Files Modified Identically on BOTH Targets
| File | Changes |
|------|---------|
| `AurelEconomy.java` | Initialize scanner + registry, register listeners + command, add reload listener, save on disable |
| `DatabaseManager.java` | Add `custom_items` table schema, migration to v2, CRUD methods |
| `MarketItems.java` | Add `CUSTOM_ITEMS` category enum value |
| `MarketManager.java` | Add `addCustomMarketItem()` method, integrate custom items into price maps |
| `MarketGUI.java` | Add CUSTOM_ITEMS category tab |
| `ShopGUI.java` | Add CUSTOM_ITEMS category tab |
| `AuctionManager.java` | Modify `getItemDisplayName()` to check custom item registry first |
| `config.yml` | Add `custom-items` configuration section |

### Target-Specific File Changes
| File | Target A (26.1.x) | Target B (1.21.x) |
|------|-------------------|-------------------|
| `plugin.yml` | `api-version: '26.1'`, add soft-deps | `api-version: '1.21'`, add soft-deps |
| `build.gradle.kts` | No changes (reflection-based, no new deps) | N/A (uses pom.xml) |
| `pom.xml` | N/A (uses build.gradle.kts) | No changes (reflection-based, no new deps) |

**Note on `plugin.yml` soft-dependencies** — add these to BOTH targets:
```yaml
softdepend: [Vault, ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item]
```

---

## Testing Checklist — Must Pass on BOTH Targets

### Target A (26.1.x — Paper 26.1.2 Build 64, Java 25)
- [ ] Plugin compiles with `paper-api:26.1.2-build-64`
- [ ] `plugin.yml` has `api-version: '26.1'`
- [ ] Plugin starts correctly with NO custom item plugins installed
- [ ] Plugin starts correctly WITH ItemsAdder installed → items auto-discovered
- [ ] Plugin starts correctly WITH Oraxen installed → items auto-discovered
- [ ] Plugin starts correctly WITH MMOItems installed → items auto-discovered
- [ ] Same item found by multiple methods → only 1 entry in registry
- [ ] Custom items appear in market GUI under CUSTOM_ITEMS category
- [ ] Custom items show correct display names in auction house
- [ ] `/customitems scan` triggers a full rescan
- [ ] `/customitems list` shows discovered items
- [ ] Custom items persist across server restarts (database)
- [ ] MySQL mode works correctly (not just SQLite)
- [ ] `/reload` triggers a rescan
- [ ] ItemsAdder `/iazip` reload triggers a rescan
- [ ] No NPEs when scanning air/null items
- [ ] No duplicate entries in the `custom_items` database table
- [ ] Price changes via `/customitems price` persist to database
- [ ] Winter Drop materials (Pale Oak, etc.) handled gracefully if present

### Target B (1.21.x — Paper 1.21.11-R0.1-SNAPSHOT, Java 21)
- [ ] Plugin compiles with `paper-api:1.21.11-R0.1-SNAPSHOT`
- [ ] `plugin.yml` has `api-version: '1.21'`
- [ ] Plugin starts correctly with NO custom item plugins installed
- [ ] Plugin starts correctly WITH ItemsAdder installed → items auto-discovered
- [ ] Plugin starts correctly WITH Oraxen installed → items auto-discovered
- [ ] Plugin starts correctly WITH MMOItems installed → items auto-discovered
- [ ] Same item found by multiple methods → only 1 entry in registry
- [ ] Custom items appear in market GUI under CUSTOM_ITEMS category
- [ ] Custom items show correct display names in auction house
- [ ] `/customitems scan` triggers a full rescan
- [ ] `/customitems list` shows discovered items
- [ ] Custom items persist across server restarts (database)
- [ ] MySQL mode works correctly (not just SQLite)
- [ ] `/reload` triggers a rescan
- [ ] ItemsAdder `/iazip` reload triggers a rescan
- [ ] No NPEs when scanning air/null items
- [ ] No duplicate entries in the `custom_items` database table
- [ ] Price changes via `/customitems price` persist to database
- [ ] `Material.matchMaterial()` returns null gracefully for materials not in 1.21.11

### Cross-Target Compatibility
- [ ] Scanner code (`com.aureleconomy.scanner` package) is 100% identical between targets
- [ ] No target-specific `if`/`else` branching in scanner logic
- [ ] All version-specific material references use `Material.matchMaterial()` with null fallback
- [ ] Both `build.gradle.kts` (Target A) and `pom.xml` (Target B) require zero new dependencies
- [ ] Database schema (`custom_items` table) is identical on both targets

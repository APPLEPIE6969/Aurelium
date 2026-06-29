#!/usr/bin/env python3
"""Apply custom item scanner modifications to Aurelium 26.1.x feature branch."""
import os, json, base64, urllib.request, urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    with open(os.path.expanduser("~/.git-credentials")) as f:
        for line in f:
            if "github.com" in line.strip():
                return line.strip().split("://")[1].split("@")[0].split(":")[1]
    raise RuntimeError("No token")

def get_file(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch, "sha": sha}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()

# === 1. AurelEconomy.java ===
print("1/7 AurelEconomy.java...")
c, sha = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", BRANCH, token)

# Add imports
c = c.replace(
    "import com.aureleconomy.database.DatabaseManager;",
    "import com.aureleconomy.database.DatabaseManager;\nimport com.aureleconomy.scanner.CustomItemRegistry;\nimport com.aureleconomy.scanner.UnifiedItemScanner;\nimport com.aureleconomy.scanner.ItemDiscoveryListener;"
)

# Add fields
c = c.replace(
    " private com.aureleconomy.web.CloudSyncManager cloudSync;",
    " private com.aureleconomy.web.CloudSyncManager cloudSync;\n private CustomItemRegistry customItemRegistry;\n private UnifiedItemScanner unifiedScanner;"
)

# Add custom item system init after orderManager.loadOrders()
init_block = """

 // Initialize custom item system
 if (getConfig().getBoolean("custom-items.enabled", true)) {
     this.customItemRegistry = new CustomItemRegistry(this);
     this.unifiedScanner = new UnifiedItemScanner(this, customItemRegistry);

     // Phase 1: Load previously discovered items from database
     customItemRegistry.loadFromDatabase(databaseManager);

     // Phase 2: Plugin API scan (delayed 1s for other plugins to load)
     getServer().getScheduler().runTaskLater(this, () -> {
         if (unifiedScanner != null) {
             unifiedScanner.scanAllPluginAPIs();
             unifiedScanner.scanPlayerInventories();
             getLogger().info("[CustomItems] Scan complete: " + customItemRegistry.getTotalItems()
                 + " unique items, " + customItemRegistry.getDuplicatesPrevented() + " duplicates prevented");
         }
     }, 20L);

     // Phase 3: Register runtime detection listeners
     getServer().getPluginManager().registerEvents(new ItemDiscoveryListener(unifiedScanner), this);

     // Phase 4: Periodic rescan
     int scanInterval = getConfig().getInt("custom-items.scan-interval-minutes", 10);
     new org.bukkit.scheduler.BukkitRunnable() {
         @Override
         public void run() {
             if (unifiedScanner != null) {
                 unifiedScanner.scanPlayerInventories();
                 customItemRegistry.saveToDatabase(databaseManager);
             }
         }
     }.runTaskTimer(this, 20L * 60L * scanInterval, 20L * 60L * scanInterval);
 }"""

c = c.replace(" orderManager.loadOrders();", " orderManager.loadOrders();" + init_block)

# Register customitems command
c = c.replace(
    " if (getCommand(\"web\") != null) {",
    """ // Custom Items command
 com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
 if (getCommand("customitems") != null) {
     getCommand("customitems").setExecutor(customItemsCmd);
     getCommand("customitems").setTabCompleter(customItemsCmd);
 }

 if (getCommand("web") != null) {"""
)

# Save on disable
c = c.replace(
    " if (marketManager != null) marketManager.persistPrices();",
    " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);"
)

# Add getters
c = c.replace(
    " public com.aureleconomy.web.CloudSyncManager getCloudSync() { return cloudSync; }",
    " public com.aureleconomy.web.CloudSyncManager getCloudSync() { return cloudSync; }\n public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }\n public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }"
)

# Add CustomItemsGUI to the GUI update task
c = c.replace(
    " else if (holder instanceof com.aureleconomy.gui.OrdersGUI gui) gui.refresh();",
    " else if (holder instanceof com.aureleconomy.gui.OrdersGUI gui) gui.refresh();\n else if (holder instanceof com.aureleconomy.gui.CustomItemsGUI gui) gui.setupItems();"
)

push_file("src/main/java/com/aureleconomy/AurelEconomy.java", c,
          "feat: integrate custom item scanner into main plugin class", BRANCH, token, sha)
print("  Done")

# === 2. DatabaseManager.java ===
print("2/7 DatabaseManager.java...")
c, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)

# Bump schema version
c = c.replace("LATEST_SCHEMA_VERSION = 1", "LATEST_SCHEMA_VERSION = 2")

# Add case 2 migration
c = c.replace(
    " addColumnIfNotExists(\"auction_offers\", \"currency\", \"VARCHAR(32)\");\n break;",
    " addColumnIfNotExists(\"auction_offers\", \"currency\", \"VARCHAR(32)\");\n break;\n case 2:\n createCustomItemsTable();\n break;"
)

# Add createCustomItemsTable method before final closing brace
method = """
 private void createCustomItemsTable() {
     try (Statement statement = connection.createStatement()) {
         if ("mysql".equals(databaseType)) {
             statement.execute("CREATE TABLE IF NOT EXISTS custom_items (" +
                 "canonical_id VARCHAR(255) PRIMARY KEY, " +
                 "source_plugin VARCHAR(64) NOT NULL, " +
                 "display_name VARCHAR(256), " +
                 "item_data TEXT NOT NULL, " +
                 "pdc_key VARCHAR(255), " +
                 "model_data_key VARCHAR(128), " +
                 "lore_hash VARCHAR(64), " +
                 "plugin_native_id VARCHAR(255), " +
                 "category VARCHAR(64), " +
                 "buy_price DOUBLE DEFAULT -1, " +
                 "sell_price DOUBLE DEFAULT -1, " +
                 "enabled TINYINT(1) DEFAULT 1, " +
                 "discovery_methods VARCHAR(256), " +
                 "first_discovered BIGINT NOT NULL, " +
                 "last_seen BIGINT NOT NULL" +
                 ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
         } else {
             statement.execute("CREATE TABLE IF NOT EXISTS custom_items (" +
                 "canonical_id TEXT PRIMARY KEY, " +
                 "source_plugin TEXT NOT NULL, " +
                 "display_name TEXT, " +
                 "item_data TEXT NOT NULL, " +
                 "pdc_key TEXT, " +
                 "model_data_key TEXT, " +
                 "lore_hash TEXT, " +
                 "plugin_native_id TEXT, " +
                 "category TEXT, " +
                 "buy_price REAL DEFAULT -1, " +
                 "sell_price REAL DEFAULT -1, " +
                 "enabled INTEGER DEFAULT 1, " +
                 "discovery_methods TEXT, " +
                 "first_discovered INTEGER NOT NULL, " +
                 "last_seen INTEGER NOT NULL" +
                 ")");
         }
     } catch (SQLException e) {
         plugin.getComponentLogger().error("Could not create custom_items table for " + databaseType + "!", e);
     }
 }
"""
# Insert before the last closing brace of the class
last_brace = c.rfind("}")
c = c[:last_brace] + method + c[last_brace:]

push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", c,
          "feat: add custom_items table and schema v2 migration", BRANCH, token, sha)
print("  Done")

# === 3. MarketItems.java ===
print("3/7 MarketItems.java...")
c, sha = get_file("src/main/java/com/aureleconomy/market/MarketItems.java", BRANCH, token)
c = c.replace(
    ' ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");',
    ' CUSTOM_ITEMS(Material.NETHER_STAR, "Custom Items"),\n ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");'
)
push_file("src/main/java/com/aureleconomy/market/MarketItems.java", c,
          "feat: add CUSTOM_ITEMS category to MarketItems enum", BRANCH, token, sha)
print("  Done")

# === 4. MarketManager.java ===
print("4/7 MarketManager.java...")
c, sha = get_file("src/main/java/com/aureleconomy/market/MarketManager.java", BRANCH, token)

c = c.replace(
    "import com.aureleconomy.market.MarketItems.Category;",
    "import com.aureleconomy.market.MarketItems.Category;\nimport com.aureleconomy.scanner.CustomMarketItem;"
)

method = """
 /**
  * Add a custom item to the market system.
  */
 public void addCustomMarketItem(String canonicalId, CustomMarketItem customItem) {
     String key = canonicalId;
     if (!entryCache.containsKey(key)) {
         MarketEntry entry = new MarketEntry(customItem.getItemStack().getType(),
                 customItem.getBuyPrice().doubleValue());
         entryCache.put(key, entry);
     }
     if (!buyPrices.containsKey(key) || customItem.getBuyPrice().compareTo(BigDecimal.ZERO) >= 0) {
         buyPrices.put(key, customItem.getBuyPrice().compareTo(BigDecimal.ZERO) >= 0
                 ? customItem.getBuyPrice() : getBuyPrice(customItem.getItemStack().getType()));
     }
     if (!sellPrices.containsKey(key) || customItem.getSellPrice().compareTo(BigDecimal.ZERO) >= 0) {
         sellPrices.put(key, customItem.getSellPrice().compareTo(BigDecimal.ZERO) >= 0
                 ? customItem.getSellPrice() : getSellPrice(customItem.getItemStack().getType()));
     }
     if (!itemCurrencies.containsKey(key)) {
         itemCurrencies.put(key, plugin.getEconomyManager().getDefaultCurrency());
     }
 }
"""
last_brace = c.rfind("}")
c = c[:last_brace] + method + c[last_brace:]

push_file("src/main/java/com/aureleconomy/market/MarketManager.java", c,
          "feat: add addCustomMarketItem method to MarketManager", BRANCH, token, sha)
print("  Done")

# === 5. AuctionManager.java ===
print("5/7 AuctionManager.java...")
c, sha = get_file("src/main/java/com/aureleconomy/auction/AuctionManager.java", BRANCH, token)

custom_check = """\t\t// Check custom item registry first
\t\tcom.aureleconomy.scanner.CustomItemRegistry registry = plugin.getCustomItemRegistry();
\t\tif (registry != null) {
\t\t\tjava.util.Optional<String> customId = registry.resolveItemId(item);
\t\t\tif (customId.isPresent()) {
\t\t\t\tcom.aureleconomy.scanner.CustomMarketItem customItem = registry.getById(customId.get());
\t\t\t\tif (customItem != null && customItem.getDisplayName() != null && !customItem.getDisplayName().isEmpty()) {
\t\t\t\t\treturn customItem.getDisplayName();
\t\t\t\t}
\t\t\t}
\t\t}
\t\t"""

# Add custom item check at start of getItemDisplayName
# Find the method and insert after the opening brace
old = "private String getItemDisplayName(ItemStack item) {\n\t\tif (item.hasItemMeta()) {"
new = "private String getItemDisplayName(ItemStack item) {\n" + custom_check + "if (item.hasItemMeta()) {"
c = c.replace(old, new)

push_file("src/main/java/com/aureleconomy/auction/AuctionManager.java", c,
          "feat: check custom item registry in getItemDisplayName", BRANCH, token, sha)
print("  Done")

# === 6. plugin.yml ===
print("6/7 plugin.yml...")
c, sha = get_file("src/main/resources/plugin.yml", BRANCH, token)

c = c.replace(
    "softdepend: [Vault]",
    "softdepend: [Vault, ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item]"
)

# Add customitems command before web
c = c.replace(
    " web:\n   description: Open the web dashboard",
    """ customitems:
  description: Manage discovered custom items
  usage: /customitems <scan|list|info|reload|toggle|price>
  permission: aureleconomy.admin
 web:
  description: Open the web dashboard"""
)

push_file("src/main/resources/plugin.yml", c,
          "feat: add customitems command and soft-deps to plugin.yml", BRANCH, token, sha)
print("  Done")

# === 7. config.yml ===
print("7/7 config.yml...")
c, sha = get_file("src/main/resources/config.yml", BRANCH, token)

custom_items_config = """
# --- Custom Item Scanner ---
custom-items:
  enabled: true
  scan-on-startup: true
  scan-interval-minutes: 10
  auto-add-to-market: true
  default-price-multiplier: 1.5
  discovery-methods:
    plugin-api: true
    pdc-scan: true
    custom-model-data: true
    lore-pattern: true
    inventory-scan: true
    interaction-detect: true
  excluded-namespaces:
    - minecraft
    - aureleconomy

"""
c = c.replace("# --- Market Items (auto-generated", custom_items_config + "# --- Market Items (auto-generated")

push_file("src/main/resources/config.yml", c,
          "feat: add custom-items config section", BRANCH, token, sha)
print("  Done")

# === 8. Update CI workflow trigger branches ===
print("8/8 Updating CI workflow...")
c, sha = get_file(".github/workflows/build.yml", BRANCH, token)
c = c.replace(
    'branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname"]',
    'branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]'
)
push_file(".github/workflows/build.yml", c,
          "ci: add feature/custom-item-scanner to trigger branches", BRANCH, token, sha)
print("  Done")

print("\nAll 26.1.x modifications pushed!")

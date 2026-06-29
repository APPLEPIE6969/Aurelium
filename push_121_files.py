#!/usr/bin/env python3
"""Push custom item scanner files to 1.21.x branch with API compat."""
import os, json, base64, urllib.request, urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

def get_token():
    with open(os.path.expanduser("~/.git-credentials")) as f:
        for line in f:
            if "github.com" in line.strip():
                return line.strip().split("://")[1].split("@")[0].split(":")[1]
    raise RuntimeError("No token")

def get_file_content(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def get_file_sha(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read()).get("sha")
    except urllib.error.HTTPError as e:
        if e.code == 404: return None
        raise

def push_file(path, content, message, branch, token, sha=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha: body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()
workspace = "/home/applepie69/.nanobot/workspace/Aurelium"

# === Push 8 new scanner files (API-compatible with both 26.1.x and 1.21.x) ===
# The scanner code uses only Paper API methods that exist in both versions
# PDC, ItemStack, Material, etc. are the same across Paper 1.21+ and 26.1+
new_files = {
    "scanner/DiscoveryMethod.java": "src/main/java/com/aureleconomy/scanner/DiscoveryMethod.java",
    "scanner/CustomMarketItem.java": "src/main/java/com/aureleconomy/scanner/CustomMarketItem.java",
    "scanner/RegistrationResult.java": "src/main/java/com/aureleconomy/scanner/RegistrationResult.java",
    "scanner/CustomItemRegistry.java": "src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java",
    "scanner/UnifiedItemScanner.java": "src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java",
    "scanner/ItemDiscoveryListener.java": "src/main/java/com/aureleconomy/scanner/ItemDiscoveryListener.java",
    "commands/CustomItemsCommand.java": "src/main/java/com/aureleconomy/commands/CustomItemsCommand.java",
    "gui/CustomItemsGUI.java": "src/main/java/com/aureleconomy/gui/CustomItemsGUI.java",
}

for local, repo in new_files.items():
    with open(os.path.join(workspace, local)) as f:
        content = f.read()
    sha = get_file_sha(repo, BRANCH, token)
    result = push_file(repo, content, f"feat: add custom item scanner - {os.path.basename(local)}", BRANCH, token, sha)
    print(f"Pushed {repo} ({result['content']['sha'][:8]})")

# === Modify existing files for 1.21.x ===

# 1. AurelEconomy.java
print("Modifying AurelEconomy.java for 1.21.x...")
content, sha = get_file_content("src/main/java/com/aureleconomy/AurelEconomy.java", BRANCH, token)

if "import com.aureleconomy.scanner.CustomItemRegistry;" not in content:
    content = content.replace(
        "import com.aureleconomy.database.DatabaseManager;",
        "import com.aureleconomy.database.DatabaseManager;\nimport com.aureleconomy.scanner.CustomItemRegistry;\nimport com.aureleconomy.scanner.UnifiedItemScanner;\nimport com.aureleconomy.scanner.ItemDiscoveryListener;"
    )

if "private CustomItemRegistry customItemRegistry;" not in content:
    content = content.replace(
        " private com.aureleconomy.web.CloudSyncManager cloudSync;",
        " private com.aureleconomy.web.CloudSyncManager cloudSync;\n private CustomItemRegistry customItemRegistry;\n private UnifiedItemScanner unifiedScanner;"
    )

if "customItemRegistry = new CustomItemRegistry" not in content:
    init_block = '''
 // Initialize custom item system
 if (getConfig().getBoolean("custom-items.enabled", true)) {
     this.customItemRegistry = new CustomItemRegistry(this);
     this.unifiedScanner = new UnifiedItemScanner(this, customItemRegistry);
     customItemRegistry.loadFromDatabase(databaseManager);
     getServer().getScheduler().runTaskLater(this, () -> {
         if (unifiedScanner != null) {
             unifiedScanner.scanAllPluginAPIs();
             unifiedScanner.scanPlayerInventories();
             getLogger().info("[CustomItems] Scan complete: " + customItemRegistry.getTotalItems() +
                 " unique items, " + customItemRegistry.getDuplicatesPrevented() + " duplicates prevented");
         }
     }, 20L);
     getServer().getPluginManager().registerEvents(new ItemDiscoveryListener(unifiedScanner), this);
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
 }'''
    content = content.replace(" orderManager.loadOrders();", " orderManager.loadOrders();" + init_block)

if "CustomItemsCommand" not in content:
    content = content.replace(
        ' if (getCommand("web") != null) {',
        ''' com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
 if (getCommand("customitems") != null) {
     getCommand("customitems").setExecutor(customItemsCmd);
     getCommand("customitems").setTabCompleter(customItemsCmd);
 }

 if (getCommand("web") != null) {'''
    )

if "customItemRegistry.saveToDatabase" not in content:
    content = content.replace(
        " if (marketManager != null) marketManager.persistPrices();",
        " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);"
    )

if "getCustomItemRegistry()" not in content:
    content = content.replace(
        " public com.aureleconomy.web.WebServer getWebServer() { return webServer; }",
        " public com.aureleconomy.web.WebServer getWebServer() { return webServer; }\n public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }\n public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }"
    )

result = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content,
                    "feat: integrate custom item scanner into main plugin class", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# 2. DatabaseManager.java
print("Modifying DatabaseManager.java for 1.21.x...")
content, sha = get_file_content("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)

if "LATEST_SCHEMA_VERSION = 2" not in content:
    content = content.replace(
        " private static final int LATEST_SCHEMA_VERSION = 1;",
        " private static final int LATEST_SCHEMA_VERSION = 2;"
    )

if "case 2:" not in content:
    # Add migration case 2
    content = content.replace(
        " addColumnIfNotExists(\"players\", \"gui_style\", \"VARCHAR(16) DEFAULT 'MODERN'\");",
        " addColumnIfNotExists(\"players\", \"gui_style\", \"VARCHAR(16) DEFAULT 'MODERN'\");\n break;\n case 2:\n createCustomItemsTable();"
    )

if "createCustomItemsTable" not in content:
    method = '''
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
'''
    content = content.rstrip()
    if content.endswith("}"):
        content = content[:-1] + method + "}"

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "feat: add custom_items table and schema v2 migration", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# 3. MarketItems.java
print("Modifying MarketItems.java for 1.21.x...")
content, sha = get_file_content("src/main/java/com/aureleconomy/market/MarketItems.java", BRANCH, token)

if "CUSTOM_ITEMS" not in content:
    content = content.replace(
        ' ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");',
        ' CUSTOM_ITEMS(Material.NETHER_STAR, "Custom Items"),\n ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");'
    )

result = push_file("src/main/java/com/aureleconomy/market/MarketItems.java", content,
                    "feat: add CUSTOM_ITEMS category to MarketItems enum", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# 4. MarketManager.java
print("Modifying MarketManager.java for 1.21.x...")
content, sha = get_file_content("src/main/java/com/aureleconomy/market/MarketManager.java", BRANCH, token)

if "addCustomMarketItem" not in content:
    content = content.replace(
        "import com.aureleconomy.market.MarketItems.Category;",
        "import com.aureleconomy.market.MarketItems.Category;\nimport com.aureleconomy.scanner.CustomMarketItem;"
    )
    method = '''
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
'''
    content = content.rstrip()
    if content.endswith("}"):
        content = content[:-1] + method + "}"

result = push_file("src/main/java/com/aureleconomy/market/MarketManager.java", content,
                    "feat: add addCustomMarketItem method to MarketManager", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# 5. AuctionManager.java
print("Modifying AuctionManager.java for 1.21.x...")
content, sha = get_file_content("src/main/java/com/aureleconomy/auction/AuctionManager.java", BRANCH, token)

if "CustomItemRegistry registry = plugin.getCustomItemRegistry()" not in content:
    content = content.replace(
        'private String getItemDisplayName(ItemStack item) {\n\t\tif (item.hasItemMeta()) {',
        '''private String getItemDisplayName(ItemStack item) {
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
\t\tif (item.hasItemMeta()) {'''
    )

result = push_file("src/main/java/com/aureleconomy/auction/AuctionManager.java", content,
                    "feat: check custom item registry in getItemDisplayName", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# 6. plugin.yml
print("Modifying plugin.yml for 1.21.x...")
content, sha = get_file_content("src/main/resources/plugin.yml", BRANCH, token)

if "customitems" not in content:
    content = content.replace(
        "softdepend: [Vault]",
        "softdepend: [Vault, ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item]"
    )
    content = content.replace(
        " web:\n  description: Open the web dashboard",
        """ customitems:
  description: Manage discovered custom items
  usage: /customitems <scan|list|info|reload|toggle|price>
  permission: aureleconomy.admin
 web:
  description: Open the web dashboard"""
    )

result = push_file("src/main/resources/plugin.yml", content,
                    "feat: add customitems command and soft-deps to plugin.yml", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# 7. config.yml
print("Modifying config.yml for 1.21.x...")
content, sha = get_file_content("src/main/resources/config.yml", BRANCH, token)

if "custom-items:" not in content:
    custom_config = """
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
    content = content.replace("# --- Market Items (auto-generated", custom_config + "# --- Market Items (auto-generated")

result = push_file("src/main/resources/config.yml", content,
                    "feat: add custom-items config section", BRANCH, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

print("\nAll 1.21.x files pushed successfully!")

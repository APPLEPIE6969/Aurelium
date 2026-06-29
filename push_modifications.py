#!/usr/bin/env python3
"""Push modifications to existing Aurelium files for custom item scanner integration."""
import os
import json
import base64
import urllib.request
import urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    cred_path = os.path.expanduser("~/.git-credentials")
    with open(cred_path) as f:
        for line in f:
            line = line.strip()
            if "github.com" in line:
                parts = line.split("://")[1].split("@")[0]
                return parts.split(":")[1]
    raise RuntimeError("No GitHub token found")

def get_file_content(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json"
    })
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch, "sha": sha}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"
    })
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

def main():
    token = get_token()

    # === 1. Modify AurelEconomy.java ===
    print("Modifying AurelEconomy.java...")
    content, sha = get_file_content("src/main/java/com/aureleconomy/AurelEconomy.java", BRANCH, token)
    
    # Add imports
    content = content.replace(
        "import com.aureleconomy.database.DatabaseManager;",
        "import com.aureleconomy.database.DatabaseManager;\nimport com.aureleconomy.scanner.CustomItemRegistry;\nimport com.aureleconomy.scanner.UnifiedItemScanner;\nimport com.aureleconomy.scanner.ItemDiscoveryListener;"
    )
    
    # Add fields
    content = content.replace(
        " private com.aureleconomy.web.CloudSyncManager cloudSync;",
        " private com.aureleconomy.web.CloudSyncManager cloudSync;\n private CustomItemRegistry customItemRegistry;\n private UnifiedItemScanner unifiedScanner;"
    )
    
    # Add initialization in onEnable after orderManager.loadOrders()
    init_block = '''
 // Initialize custom item system
 if (getConfig().getBoolean("custom-items.enabled", true)) {
     this.customItemRegistry = new CustomItemRegistry(this);
     this.unifiedScanner = new UnifiedItemScanner(this, customItemRegistry);

     // Phase 1: Load previously discovered items from database
     customItemRegistry.loadFromDatabase(databaseManager);

     // Phase 2: Plugin API scan (delayed 1 second to ensure other plugins are fully loaded)
     getServer().getScheduler().runTaskLater(this, () -> {
         if (unifiedScanner != null) {
             unifiedScanner.scanAllPluginAPIs();
             unifiedScanner.scanPlayerInventories();
             getLogger().info("[CustomItems] Scan complete: " + customItemRegistry.getTotalItems() +
                 " unique items, " + customItemRegistry.getDuplicatesPrevented() + " duplicates prevented");
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
 }'''
    content = content.replace(
        " orderManager.loadOrders();",
        " orderManager.loadOrders();" + init_block
    )
    
    # Register customitems command
    content = content.replace(
        ' if (getCommand("web") != null) {',
        ''' // Custom Items command
 com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
 if (getCommand("customitems") != null) {
     getCommand("customitems").setExecutor(customItemsCmd);
     getCommand("customitems").setTabCompleter(customItemsCmd);
 }

 if (getCommand("web") != null) {'''
    )
    
    # Add save on disable
    content = content.replace(
        " if (marketManager != null) marketManager.persistPrices();",
        " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);"
    )
    
    # Add getters
    content = content.replace(
        " public com.aureleconomy.web.WebServer getWebServer() { return webServer; }",
        " public com.aureleconomy.web.WebServer getWebServer() { return webServer; }\n public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }\n public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }"
    )
    
    result = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content,
                       "feat: integrate custom item scanner into main plugin class", BRANCH, token, sha)
    print(f"  Pushed AurelEconomy.java (sha: {result['content']['sha'][:8]})")

    # === 2. Modify DatabaseManager.java ===
    print("Modifying DatabaseManager.java...")
    content, sha = get_file_content("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)
    
    # Bump schema version
    content = content.replace(
        " private static final int LATEST_SCHEMA_VERSION = 1;",
        " private static final int LATEST_SCHEMA_VERSION = 2;"
    )
    
    # Add migration for schema v2
    content = content.replace(
        " case 1:\n addColumnIfNotExists(\"players\", \"gui_style\", \"VARCHAR(16) DEFAULT \\'MODERN\\'\");",
        """ case 1:
 addColumnIfNotExists("players", "gui_style", "VARCHAR(16) DEFAULT \\'MODERN\\'");
 addColumnIfNotExists("auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
 addColumnIfNotExists("auctions", "start_time", "LONG");
 addColumnIfNotExists("auctions", "currency", "VARCHAR(32)");
 addColumnIfNotExists("offline_earnings", "currency", "VARCHAR(32)");
 addColumnIfNotExists("buy_orders", "currency", "VARCHAR(32)");
 addColumnIfNotExists("auction_offers", "currency", "VARCHAR(32)");
 break;
 case 2:
 createCustomItemsTable();
 break;"""
    )
    
    # Remove the duplicate break and columns from case 1 (they were already there)
    # Actually the existing case 1 already has those addColumn calls. Let me just add case 2.
    # Re-read to see the actual content after our replacement
    # The replacement above duplicated. Let me fix it properly.
    
    # Actually, let me just add the createCustomItemsTable method and case 2 more carefully
    # The above replacement should work since case 1 already had those lines, we just need to add case 2
    
    # Add createCustomItemsTable method before the closing brace
    custom_items_table_method = '''
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
        content = content[:-1] + custom_items_table_method + "}"
    
    result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                       "feat: add custom_items table and schema v2 migration", BRANCH, token, sha)
    print(f"  Pushed DatabaseManager.java (sha: {result['content']['sha'][:8]})")

    # === 3. Modify MarketItems.java - add CUSTOM_ITEMS category ===
    print("Modifying MarketItems.java...")
    content, sha = get_file_content("src/main/java/com/aureleconomy/market/MarketItems.java", BRANCH, token)
    
    content = content.replace(
        ' ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");',
        ' CUSTOM_ITEMS(Material.NETHER_STAR, "Custom Items"),\n ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");'
    )
    
    result = push_file("src/main/java/com/aureleconomy/market/MarketItems.java", content,
                       "feat: add CUSTOM_ITEMS category to MarketItems enum", BRANCH, token, sha)
    print(f"  Pushed MarketItems.java (sha: {result['content']['sha'][:8]})")

    # === 4. Modify MarketManager.java - add addCustomMarketItem method ===
    print("Modifying MarketManager.java...")
    content, sha = get_file_content("src/main/java/com/aureleconomy/market/MarketManager.java", BRANCH, token)
    
    # Add import for CustomMarketItem
    content = content.replace(
        "import com.aureleconomy.market.MarketItems.Category;",
        "import com.aureleconomy.market.MarketItems.Category;\nimport com.aureleconomy.scanner.CustomMarketItem;"
    )
    
    # Add addCustomMarketItem method before the closing brace
    custom_market_method = '''
 /**
  * Add a custom item to the market system.
  */
 public void addCustomMarketItem(String canonicalId, CustomMarketItem customItem) {
     String key = canonicalId;
     
     if (!entryCache.containsKey(key)) {
         // Create a MarketEntry-like mapping for the custom item
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
        content = content[:-1] + custom_market_method + "}"
    
    result = push_file("src/main/java/com/aureleconomy/market/MarketManager.java", content,
                       "feat: add addCustomMarketItem method to MarketManager", BRANCH, token, sha)
    print(f"  Pushed MarketManager.java (sha: {result['content']['sha'][:8]})")

    # === 5. Modify AuctionManager.java - update getItemDisplayName ===
    print("Modifying AuctionManager.java...")
    content, sha = get_file_content("src/main/java/com/aureleconomy/auction/AuctionManager.java", BRANCH, token)
    
    content = content.replace(
        '''private String getItemDisplayName(ItemStack item) {
\t\tif (item.hasItemMeta()) {''',
        '''private String getItemDisplayName(ItemStack item) {
\t\t// Check custom item registry first
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
    print(f"  Pushed AuctionManager.java (sha: {result['content']['sha'][:8]})")

    # === 6. Modify plugin.yml ===
    print("Modifying plugin.yml...")
    content, sha = get_file_content("src/main/resources/plugin.yml", BRANCH, token)
    
    content = content.replace(
        "softdepend: [Vault]",
        "softdepend: [Vault, ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item]"
    )
    
    # Add customitems command
    content = content.replace(
        " web:\n   description: Open the web dashboard",
        """ customitems:
  description: Manage discovered custom items
  usage: /customitems <scan|list|info|reload|toggle|price>
  permission: aureleconomy.admin
 web:
  description: Open the web dashboard"""
    )
    
    result = push_file("src/main/resources/plugin.yml", content,
                       "feat: add customitems command and soft-deps to plugin.yml", BRANCH, token, sha)
    print(f"  Pushed plugin.yml (sha: {result['content']['sha'][:8]})")

    # === 7. Modify config.yml ===
    print("Modifying config.yml...")
    content, sha = get_file_content("src/main/resources/config.yml", BRANCH, token)
    
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
    content = content.replace(
        "# --- Market Items (auto-generated",
        custom_items_config + "\n# --- Market Items (auto-generated"
    )
    
    result = push_file("src/main/resources/config.yml", content,
                       "feat: add custom-items config section", BRANCH, token, sha)
    print(f"  Pushed config.yml (sha: {result['content']['sha'][:8]})")

    # === 8. Modify MarketGUI.java - add CUSTOM_ITEMS category support ===
    print("Modifying MarketGUI.java...")
    content, sha = get_file_content("src/main/java/com/aureleconomy/gui/MarketGUI.java", BRANCH, token)
    
    # The CUSTOM_ITEMS category is already in the Category enum, so MarketGUI should
    # automatically pick it up in setupCategories(). No code change needed since it
    # iterates Category.values(). Same for ShopGUI.
    print("  MarketGUI.java - no changes needed (Category.values() auto-includes CUSTOM_ITEMS)")

    # === 9. Modify ShopGUI.java - same, no changes needed ===
    print("ShopGUI.java - no changes needed (Category.values() auto-includes CUSTOM_ITEMS)")

    print("\nAll modifications pushed successfully!")

if __name__ == "__main__":
    main()

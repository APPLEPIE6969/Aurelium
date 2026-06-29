#!/usr/bin/env python3
"""Apply remaining modifications to 1.21.x feature branch."""
import os, json, base64, urllib.request, urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

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

# === 1. AurelEconomy.java - add CustomItemsGUI to GUI update task, add save on disable ===
print("1/6 AurelEconomy.java...")
c, sha = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", BRANCH, token)

# Add CustomItemsGUI to GUI update task
c = c.replace(
    " else if (holder instanceof com.aureleconomy.gui.OrdersGUI gui) gui.refresh();",
    " else if (holder instanceof com.aureleconomy.gui.OrdersGUI gui) gui.refresh();\n else if (holder instanceof com.aureleconomy.gui.CustomItemsGUI gui) gui.setupItems();"
)

# Add save on disable
c = c.replace(
    " if (marketManager != null) marketManager.persistPrices();\n if (databaseManager != null) databaseManager.close();",
    " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (databaseManager != null) databaseManager.close();"
)

push_file("src/main/java/com/aureleconomy/AurelEconomy.java", c,
          "feat: add CustomItemsGUI to update task + save on disable", BRANCH, token, sha)
print("  Done")

# === 2. DatabaseManager.java ===
print("2/6 DatabaseManager.java...")
c, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)

c = c.replace("LATEST_SCHEMA_VERSION = 1", "LATEST_SCHEMA_VERSION = 2")

# Add case 2 migration
old = ' addColumnIfNotExists("auction_offers", "currency", "VARCHAR(32)");\n break;'
new = old + '\n case 2:\n createCustomItemsTable();\n break;'
c = c.replace(old, new)

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
last_brace = c.rfind("}")
c = c[:last_brace] + method + c[last_brace:]

push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", c,
          "feat: add custom_items table and schema v2 migration", BRANCH, token, sha)
print("  Done")

# === 3. MarketItems.java ===
print("3/6 MarketItems.java...")
c, sha = get_file("src/main/java/com/aureleconomy/market/MarketItems.java", BRANCH, token)
c = c.replace(
    ' ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");',
    ' CUSTOM_ITEMS(Material.NETHER_STAR, "Custom Items"),\n ALL_ITEMS(Material.COMPASS, "All Items (Searchable)");'
)
push_file("src/main/java/com/aureleconomy/market/MarketItems.java", c,
          "feat: add CUSTOM_ITEMS category to MarketItems enum", BRANCH, token, sha)
print("  Done")

# === 4. MarketManager.java ===
print("4/6 MarketManager.java...")
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
print("5/6 AuctionManager.java...")
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

old = "private String getItemDisplayName(ItemStack item) {\n\t\tif (item.hasItemMeta()) {"
new = "private String getItemDisplayName(ItemStack item) {\n" + custom_check + "if (item.hasItemMeta()) {"
c = c.replace(old, new)

push_file("src/main/java/com/aureleconomy/auction/AuctionManager.java", c,
          "feat: check custom item registry in getItemDisplayName", BRANCH, token, sha)
print("  Done")

# === 6. plugin.yml + config.yml ===
print("6/6 plugin.yml + config.yml...")

# plugin.yml
c, sha = get_file("src/main/resources/plugin.yml", BRANCH, token)
c = c.replace("softdepend: [Vault]", "softdepend: [Vault, ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item]")
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
print("  plugin.yml Done")

# config.yml
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
print("  config.yml Done")

# === 7. CI workflow - update trigger branches ===
print("7/7 CI workflow...")
c, sha = get_file(".github/workflows/build.yml", BRANCH, token)
c = c.replace(
    'branches: ["1.4.3", main, "compat/paper-1.21"]',
    'branches: ["1.4.3", main, "compat/paper-1.21", "feature/custom-item-scanner-1.21"]'
)
# Also add to PR triggers
c = c.replace(
    'branches: ["1.4.3", main, "compat/paper-1.21"]',
    'branches: ["1.4.3", main, "compat/paper-1.21", "feature/custom-item-scanner-1.21"]'
)
push_file(".github/workflows/build.yml", c,
          "ci: add feature/custom-item-scanner-1.21 to trigger branches", BRANCH, token, sha)
print("  Done")

# === 8. Delete stale pom.xml ===
print("8/8 Delete stale pom.xml...")
c, sha = get_file("pom.xml", BRANCH, token)
url = f"https://api.github.com/repos/{REPO}/contents/pom.xml"
body = json.dumps({"message": "chore: remove stale pom.xml (project uses Gradle)", "branch": BRANCH, "sha": sha})
req = urllib.request.Request(url, data=body.encode("utf-8"), method="DELETE", headers={
    "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
with urllib.request.urlopen(req) as resp:
    print("  pom.xml deleted")

print("\nAll 1.21.x modifications pushed!")

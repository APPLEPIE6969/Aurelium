#!/usr/bin/env python3
"""Fix AurelEconomy.java - remove duplicate code from the 1.21.x branch."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

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
content, sha = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", BRANCH, token)

# Fix duplicate imports
content = content.replace(
    "import com.aureleconomy.scanner.CustomItemRegistry;\nimport com.aureleconomy.scanner.UnifiedItemScanner;\nimport com.aureleconomy.scanner.ItemDiscoveryListener;\nimport com.aureleconomy.scanner.CustomItemRegistry;\nimport com.aureleconomy.scanner.UnifiedItemScanner;\nimport com.aureleconomy.scanner.ItemDiscoveryListener;",
    "import com.aureleconomy.scanner.CustomItemRegistry;\nimport com.aureleconomy.scanner.UnifiedItemScanner;\nimport com.aureleconomy.scanner.ItemDiscoveryListener;"
)

# Fix duplicate fields
content = content.replace(
    " private CustomItemRegistry customItemRegistry;\n private UnifiedItemScanner unifiedScanner;\n private CustomItemRegistry customItemRegistry;\n private UnifiedItemScanner unifiedScanner;",
    " private CustomItemRegistry customItemRegistry;\n private UnifiedItemScanner unifiedScanner;"
)

# Fix duplicate init block (second one has "1 second" comment)
init2 = """ // Initialize custom item system
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
 }"""

content = content.replace(init2, "", 1)

# Fix duplicate save calls
content = content.replace(
    " if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);",
    " if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);"
)

# Fix duplicate command registration
cmd_dup = """ // Custom Items command
 com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
 if (getCommand("customitems") != null) {
 getCommand("customitems").setExecutor(customItemsCmd);
 getCommand("customitems").setTabCompleter(customItemsCmd);
 }

 // Custom Items command
 com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
 if (getCommand("customitems") != null) {
 getCommand("customitems").setExecutor(customItemsCmd);
 getCommand("customitems").setTabCompleter(customItemsCmd);
 }"""

cmd_single = """ // Custom Items command
 com.aureleconomy.commands.CustomItemsCommand customItemsCmd = new com.aureleconomy.commands.CustomItemsCommand(this);
 if (getCommand("customitems") != null) {
 getCommand("customitems").setExecutor(customItemsCmd);
 getCommand("customitems").setTabCompleter(customItemsCmd);
 }"""

content = content.replace(cmd_dup, cmd_single)

# Fix duplicate getters
content = content.replace(
    " public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }\n public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }\n public com.aureleconomy.web.CloudSyncManager getCloudSync() { return cloudSync; }\n public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }\n public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }",
    " public com.aureleconomy.web.CloudSyncManager getCloudSync() { return cloudSync; }\n public CustomItemRegistry getCustomItemRegistry() { return customItemRegistry; }\n public UnifiedItemScanner getUnifiedScanner() { return unifiedScanner; }"
)

# Fix duplicate onDisable
content = content.replace(
    " if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (databaseManager != null) databaseManager.close();",
    " if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (databaseManager != null) databaseManager.close();"
)

result = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content,
                    "fix: remove duplicate code in AurelEconomy.java (1.21.x)", BRANCH, token, sha)
print(f"Fixed AurelEconomy.java on {BRANCH} (sha: {result['content']['sha'][:8]})")

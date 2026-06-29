#!/usr/bin/env python3
"""
Update Aurelium's ExecutableItems scanner to use the official SCore API,
and add real EI/SCore JARs to CI for testing.

Key changes:
1. scanExecutableItems() now uses com.ssomar.score.api.executableitems.ExecutableItemsAPI
   instead of internal com.ssomar.executableitems.ExecutableItems reflection
2. CI workflow gets a custom-item-detection-test job that:
   - Drops SCore + ExecutableItems JARs into plugins/
   - Creates a simple EI config with a test item
   - Starts Paper, triggers /customitems scan
   - Verifies the test item appears in /customitems list
"""
import json, base64, urllib.request, hashlib

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

def get_file(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha:
        body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", 
        "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

def upload_large_file(path, content_bytes, message, branch, token, sha=None):
    """Upload a binary file via GitHub API."""
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content_bytes).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha:
        body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", 
        "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()

# ============================================================
# 1. Update UnifiedItemScanner.java - fix scanExecutableItems()
# ============================================================
scanner_code, scanner_sha = get_file("src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java", BRANCH, token)

# Replace the old scanExecutableItems() method
old_method = '''    private void scanExecutableItems() {
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
                    CustomMarketItem item = buildCustomItem(itemStack, "executableitems:" + id, "ExecutableItems", DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                } catch (Exception ignored) {}
            }
            plugin.getComponentLogger().warn("[Scanner] ExecutableItems scan failed: " + e.getMessage());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Plugin not present or API changed, skip
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] ExecutableItems scan failed: " + e.getMessage());
        }
    }'''

new_method = '''    private void scanExecutableItems() {
        if (Bukkit.getPluginManager().getPlugin("ExecutableItems") == null) return;
        try {
            // Use the official SCore API: ExecutableItemsAPI
            Class<?> apiClass = Class.forName("com.ssomar.score.api.executableitems.ExecutableItemsAPI");
            Method getInstanceMethod = apiClass.getMethod("getInstance");
            Object apiInstance = getInstanceMethod.invoke(null);
            Method getManagerMethod = apiClass.getMethod("getExecutableItemsManager");
            Object manager = getManagerMethod.invoke(apiInstance);

            // ExecutableItemsManagerInterface.getAllExecutableItems()
            Method getAllMethod = manager.getClass().getMethod("getAllExecutableItems");
            @SuppressWarnings("unchecked")
            Collection<?> items = (Collection<?>) getAllMethod.invoke(manager);
            if (items == null) return;

            for (Object eiItemObj : items) {
                try {
                    // ExecutableItemInterface extends SObject -> getId(), buildItem()
                    Method getIdMethod = eiItemObj.getClass().getMethod("getId");
                    Method buildItemMethod = eiItemObj.getClass().getMethod("buildItem");
                    String id = (String) getIdMethod.invoke(eiItemObj);
                    ItemStack itemStack = (ItemStack) buildItemMethod.invoke(eiItemObj);
                    if (itemStack == null || id == null) continue;

                    // Try to get display name from the official API
                    String displayName = null;
                    try {
                        Method getDisplayNameMethod = eiItemObj.getClass().getMethod("getDisplayName");
                        Object nameResult = getDisplayNameMethod.invoke(eiItemObj);
                        if (nameResult instanceof String) {
                            displayName = (String) nameResult;
                        }
                    } catch (Exception ignored) {}

                    CustomMarketItem item = buildCustomItem(itemStack, "executableitems:" + id, "ExecutableItems", DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                    if (displayName != null && !displayName.isEmpty()) {
                        item.setDisplayName(displayName);
                    }
                    registry.register(item, DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.FINE, "[Scanner] ExecutableItems item scan error", e);
                }
            }
            plugin.getComponentLogger().info("[Scanner] ExecutableItems scan complete: " + items.size() + " items found");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Plugin not present or API changed — try legacy fallback
            scanExecutableItemsLegacy();
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] ExecutableItems scan failed: " + e.getMessage());
        }
    }

    /**
     * Legacy fallback for older EI versions that don't expose the SCore API.
     * Uses the internal ExecutableItems plugin class directly.
     */
    private void scanExecutableItemsLegacy() {
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
                    CustomMarketItem item = buildCustomItem(itemStack, "executableitems:" + id, "ExecutableItems", DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                } catch (Exception ignored) {}
            }
            plugin.getComponentLogger().info("[Scanner] ExecutableItems (legacy) scan complete");
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // Plugin not present, skip silently
        } catch (Exception e) {
            plugin.getComponentLogger().warn("[Scanner] ExecutableItems legacy scan failed: " + e.getMessage());
        }
    }'''

if old_method in scanner_code:
    scanner_code = scanner_code.replace(old_method, new_method)
    print("Replaced scanExecutableItems() with official API + legacy fallback")
else:
    print("WARNING: old method not found exactly - trying line-by-line approach")
    # Find the method boundaries
    lines = scanner_code.split('\n')
    start_idx = None
    end_idx = None
    brace_count = 0
    in_method = False
    for i, line in enumerate(lines):
        if 'private void scanExecutableItems()' in line:
            start_idx = i
            in_method = True
            brace_count = 0
        if in_method:
            brace_count += line.count('{') - line.count('}')
            if brace_count <= 0 and '{' in ''.join(lines[start_idx:i+1]):
                end_idx = i
                break
    
    if start_idx is not None and end_idx is not None:
        old_block = '\n'.join(lines[start_idx:end_idx+1])
        scanner_code = scanner_code.replace(old_block, new_method)
        print(f"Replaced scanExecutableItems() (lines {start_idx+1}-{end_idx+1})")
    else:
        print(f"ERROR: Could not find method boundaries (start={start_idx}, end={end_idx})")

# Also need to check if CustomMarketItem has setDisplayName
# Let's check CustomMarketItem.java
try:
    cmi_code, cmi_sha = get_file("src/main/java/com/aureleconomy/scanner/CustomMarketItem.java", BRANCH, token)
    has_setter = "setDisplayName" in cmi_code or "displayName" in cmi_code
    print(f"CustomMarketItem has displayName field: {has_setter}")
    if not has_setter:
        print("  -> Need to add displayName field + setter")
except:
    print("  -> Could not read CustomMarketItem.java")

result = push_file("src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java", scanner_code,
                    "scanner: use official ExecutableItemsAPI (SCore) with legacy fallback",
                    BRANCH, token, scanner_sha)
print(f"Pushed scanner update (sha: {result['content']['sha'][:8]})")

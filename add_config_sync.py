#!/usr/bin/env python3
"""Add syncToConfig and loadConfigOverrides to CustomItemRegistry on both branches."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"

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

def push_file(path, content, message, branch, token, sha):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch, "sha": sha}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()

METHOD_CODE = '''
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
 }'''

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    print(f"\n=== {branch} ===")
    content, sha = get_file("src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java", branch, token)
    
    if "syncToConfig" in content:
        print("  syncToConfig already exists, skipping")
        continue
    
    lines = content.split("\n")
    
    # Find insertion point - after getDuplicatesPrevented
    insert_idx = None
    for i, line in enumerate(lines):
        if "public long getDuplicatesPrevented()" in line:
            # Find closing brace of this method
            for j in range(i, min(i+5, len(lines))):
                if lines[j].strip() == "}":
                    insert_idx = j + 1
                    break
            break
    
    if insert_idx is None:
        print("  Could not find insertion point!")
        continue
    
    lines.insert(insert_idx, METHOD_CODE)
    content = "\n".join(lines)
    
    result = push_file("src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java", content,
                        f"feat: add config auto-sync for discovered custom items", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")
PYEOF
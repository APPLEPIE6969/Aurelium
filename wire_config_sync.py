#!/usr/bin/env python3
"""Wire syncToConfig and loadConfigOverrides into AurelEconomy.java on both branches."""
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

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    print(f"\n=== {branch} ===")
    content, sha = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", branch, token)
    
    changed = False
    
    # 1. Add loadConfigOverrides() call after loadFromDatabase
    if "loadConfigOverrides()" not in content:
        content = content.replace(
            "customItemRegistry.loadFromDatabase(databaseManager);",
            "customItemRegistry.loadFromDatabase(databaseManager);\n        customItemRegistry.loadConfigOverrides();"
        )
        print("  Added loadConfigOverrides() after loadFromDatabase")
        changed = True
    
    # 2. Add syncToConfig() call after scan complete log message
    if "syncToConfig()" not in content:
        old_log = 'getLogger().info("[CustomItems] Scan complete: " + customItemRegistry.getTotalItems() + " unique items, " + customItemRegistry.getDuplicatesPrevented() + " duplicates prevented");'
        new_log = old_log + '\n            customItemRegistry.syncToConfig();'
        content = content.replace(old_log, new_log)
        print("  Added syncToConfig() after scan completion")
        changed = True
    
    if not changed:
        print("  Already wired, skipping")
        continue
    
    result = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content,
                        "feat: wire config auto-sync into startup and scan cycle", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")

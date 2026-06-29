#!/usr/bin/env python3
"""Add createCustomItemsTable() method to 1.21.x DatabaseManager.java."""
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
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)

# Add createCustomItemsTable() method before the closing brace of the class
method = '''
 private void createCustomItemsTable() {
 try (Statement statement = connection.createStatement()) {
 if (isMySQL()) {
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

# Insert before the last closing brace
last_brace = content.rfind("}")
content = content[:last_brace] + method + content[last_brace:]

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: add missing createCustomItemsTable() method (1.21.x)", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

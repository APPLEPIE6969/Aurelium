#!/usr/bin/env python3
"""Fix build.gradle.kts on Latest branch - update Paper API + version."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "Latest"

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
content, sha = get_file("build.gradle.kts", BRANCH, token)

# Update version
content = content.replace('version = "1.4.3"', 'version = "1.4.5"')

# Update Paper API to latest build
content = content.replace(
    'compileOnly("io.papermc.paper:paper-api:26.1.2.build.53-stable")',
    'compileOnly("io.papermc.paper:paper-api:26.1.2.build.64-stable")'
)

result = push_file("build.gradle.kts", content, "fix: update Paper API to 26.1.2.build.64-stable, version 1.4.5", BRANCH, token, sha)
print(f"Fixed build.gradle.kts (sha: {result['content']['sha'][:8]})")

# Also update plugin.yml version
content2, sha2 = get_file("src/main/resources/plugin.yml", BRANCH, token)
content2 = content2.replace("version: '1.4.3'", "version: '1.4.5'")

result2 = push_file("src/main/resources/plugin.yml", content2, "fix: bump version to 1.4.5", BRANCH, token, sha2)
print(f"Fixed plugin.yml version (sha: {result2['content']['sha'][:8]})")

# Also fix onDisable to save customItemRegistry
content3, sha3 = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", BRANCH, token)
if "customItemRegistry.saveToDatabase" not in content3.split("onDisable")[1].split("}")[0]:
    content3 = content3.replace(
        " if (marketManager != null) marketManager.persistPrices();\n if (databaseManager != null) databaseManager.close();",
        " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (databaseManager != null) databaseManager.close();"
    )
    result3 = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content3, "fix: save customItemRegistry on disable", BRANCH, token, sha3)
    print(f"Fixed onDisable (sha: {result3['content']['sha'][:8]})")
else:
    print("onDisable already saves customItemRegistry")

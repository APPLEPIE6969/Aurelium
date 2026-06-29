#!/usr/bin/env python3
"""Add missing customItemRegistry.saveToDatabase to 1.21.x branch."""
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

# Add customItemRegistry.saveToDatabase to the periodic timer
content = content.replace(
    " if (marketManager != null) marketManager.persistPrices();\n }, 6000L, 6000L);",
    " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n }, 6000L, 6000L);"
)

# Add customItemRegistry.saveToDatabase to onDisable
content = content.replace(
    " if (marketManager != null) marketManager.persistPrices();\n if (databaseManager != null) databaseManager.close();",
    " if (marketManager != null) marketManager.persistPrices();\n if (customItemRegistry != null) customItemRegistry.saveToDatabase(databaseManager);\n if (databaseManager != null) databaseManager.close();"
)

result = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content,
                    "fix: add missing customItemRegistry save calls in timer and onDisable", BRANCH, token, sha)
print(f"Fixed 1.21.x AurelEconomy.java (sha: {result['content']['sha'][:8]})")

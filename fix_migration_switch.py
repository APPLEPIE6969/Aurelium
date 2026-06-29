#!/usr/bin/env python3
"""Add case 2 to applyMigration switch in DatabaseManager.java on 26.x branch."""
import json, base64, urllib.request

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

# Add case 2 to applyMigration switch - insert before the closing of case 1
old = """ case 1:
 addColumnIfNotExists("players", "gui_style", "VARCHAR(16) DEFAULT 'MODERN'");
 addColumnIfNotExists("auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
 addColumnIfNotExists("auctions", "start_time", "LONG");
 addColumnIfNotExists("auctions", "currency", "VARCHAR(32)");
 addColumnIfNotExists("offline_earnings", "currency", "VARCHAR(32)");
 addColumnIfNotExists("buy_orders", "currency", "VARCHAR(32)");
 addColumnIfNotExists("auction_offers", "currency", "VARCHAR(32)");
 break;
 }"""

new = """ case 1:
 addColumnIfNotExists("players", "gui_style", "VARCHAR(16) DEFAULT 'MODERN'");
 addColumnIfNotExists("auctions", "listing_fee", "DOUBLE DEFAULT 0.0");
 addColumnIfNotExists("auctions", "start_time", "LONG");
 addColumnIfNotExists("auctions", "currency", "VARCHAR(32)");
 addColumnIfNotExists("offline_earnings", "currency", "VARCHAR(32)");
 addColumnIfNotExists("buy_orders", "currency", "VARCHAR(32)");
 addColumnIfNotExists("auction_offers", "currency", "VARCHAR(32)");
 break;
 case 2:
 createCustomItemsTable();
 break;
 }"""

if "case 2:" not in content:
    content = content.replace(old, new)
    print("Added case 2 to applyMigration switch")
else:
    print("case 2 already exists in switch")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: add case 2 migration to create custom_items table", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

# Also fix the 1.21.x branch - check if it has the same issue
print("\nChecking 1.21.x branch...")
branch2 = "feature/custom-item-scanner-1.21"
content2, sha2 = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", branch2, token)

if "case 2:" in content2:
    # Already has case 2
    lines = content2.split("\n")
    for i, line in enumerate(lines):
        if "case 2:" in line:
            for j in range(i, min(i+5, len(lines))):
                print(f"  {lines[j]}")
            break
else:
    print("1.21.x also missing case 2 - fixing...")
    content2 = content2.replace(old, new)
    result2 = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content2,
                        "fix: add case 2 migration to create custom_items table (1.21.x)", branch2, token, sha2)
    print(f"Pushed 1.21.x (sha: {result2['content']['sha'][:8]})")

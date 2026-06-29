#!/usr/bin/env python3
"""Add case 2 to applyMigration switch - exact match replacement."""
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

# Find the exact text to replace - look for the switch block ending
# Use the exact text from the file
old_text = """ break;
 }
 }

 private void createOffersTable"""

new_text = """ break;
 case 2:
 createCustomItemsTable();
 break;
 }
 }

 private void createOffersTable"""

content = content.replace(old_text, new_text, 1)

if "case 2:" in content:
    print("case 2: added successfully")
else:
    print("ERROR: replacement still failed!")
    # Debug: show the exact characters around the target
    idx = content.find("createOffersTable")
    if idx > 0:
        print(repr(content[idx-100:idx+30]))

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: add case 2 migration for custom_items table creation", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

#!/usr/bin/env python3
"""Fix plugin.yml on both branches - add customitems command, fix api-version."""
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

# Fix 26.x branch - add customitems command
print("Fixing 26.x plugin.yml...")
branch = "feature/custom-item-scanner"
content, sha = get_file("src/main/resources/plugin.yml", branch, token)

# Add customitems command before web command
if "customitems" not in content:
    content = content.replace(
        " web:\n  description: Open the web dashboard",
        " customitems:\n  description: Manage discovered custom items\n  usage: /customitems <scan|list|info|reload|toggle|price>\n  permission: aureleconomy.admin\n web:\n  description: Open the web dashboard"
    )

result = push_file("src/main/resources/plugin.yml", content,
                    "fix: add customitems command to plugin.yml", branch, token, sha)
print(f"  Fixed 26.x plugin.yml (sha: {result['content']['sha'][:8]})")

# Fix 1.21.x branch - add customitems command + fix api-version
print("Fixing 1.21.x plugin.yml...")
branch = "feature/custom-item-scanner-1.21"
content, sha = get_file("src/main/resources/plugin.yml", branch, token)

# Fix api-version
content = content.replace("api-version: '1.21.11'", "api-version: '1.21'")

# Add customitems command
if "customitems" not in content:
    content = content.replace(
        " web:\n  description: Open the web dashboard",
        " customitems:\n  description: Manage discovered custom items\n  usage: /customitems <scan|list|info|reload|toggle|price>\n  permission: aureleconomy.admin\n web:\n  description: Open the web dashboard"
    )

result = push_file("src/main/resources/plugin.yml", content,
                    "fix: add customitems command + fix api-version for 1.21.x", branch, token, sha)
print(f"  Fixed 1.21.x plugin.yml (sha: {result['content']['sha'][:8]})")

print("Done!")

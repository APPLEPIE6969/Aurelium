#!/usr/bin/env python3
"""Add discovered-items section comment to config.yml on both branches."""
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

discovered_section = """
# --- Discovered Custom Items ---
# Items below are auto-populated when custom item plugins (ItemsAdder, Oraxen, etc.) are detected.
# You can override prices, enable/disable items, or change display names here.
# Changes take effect on next server restart. Delete an entry to reset it to scanner defaults.
# Example:
#   itemsadder:ruby_sword:
#     source-plugin: ItemsAdder
#     display-name: "Ruby Sword"
#     category: CUSTOM_ITEMS
#     buy-price: 500.0
#     sell-price: 250.0
#     enabled: true
discovered-items: {}
"""

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    print(f"\n=== {branch} ===")
    content, sha = get_file("src/main/resources/config.yml", branch, token)
    
    if "discovered-items:" in content:
        print("  discovered-items section already exists, skipping")
        continue
    
    # Append the section at the end
    content = content.rstrip() + "\n" + discovered_section
    
    result = push_file("src/main/resources/config.yml", content,
                        "feat: add discovered-items section to config.yml for admin overrides", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")

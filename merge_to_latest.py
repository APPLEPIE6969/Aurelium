#!/usr/bin/env python3
"""Merge custom item scanner into Latest branch from feature/custom-item-scanner."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
SOURCE_BRANCH = "feature/custom-item-scanner"
TARGET_BRANCH = "Latest"

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

def get_file_sha(path, branch, token):
    try:
        url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
            return data["sha"]
    except:
        return None

def push_file(path, content, message, branch, token, sha=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha:
        body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()

# 1. Copy scanner package files (6 files)
scanner_files = [
    "src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java",
    "src/main/java/com/aureleconomy/scanner/CustomMarketItem.java",
    "src/main/java/com/aureleconomy/scanner/DiscoveryMethod.java",
    "src/main/java/com/aureleconomy/scanner/ItemDiscoveryListener.java",
    "src/main/java/com/aureleconomy/scanner/RegistrationResult.java",
    "src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java",
]

for f in scanner_files:
    print(f"Copying {f}...")
    content, _ = get_file(f, SOURCE_BRANCH, token)
    existing_sha = get_file_sha(f, TARGET_BRANCH, token)
    result = push_file(f, content, f"feat: add {f.split('/')[-1]} from scanner feature", TARGET_BRANCH, token, existing_sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 2. Copy modified files from feature branch
modified_files = [
    "src/main/java/com/aureleconomy/AurelEconomy.java",
    "src/main/java/com/aureleconomy/auction/AuctionManager.java",
    "src/main/java/com/aureleconomy/market/MarketManager.java",
    "src/main/java/com/aureleconomy/market/MarketItems.java",
    "src/main/java/com/aureleconomy/database/DatabaseManager.java",
    "src/main/java/com/aureleconomy/gui/CustomItemsGUI.java",
    "src/main/java/com/aureleconomy/commands/CustomItemsCommand.java",
    "src/main/resources/plugin.yml",
    "src/main/resources/config.yml",
]

for f in modified_files:
    print(f"Copying modified {f}...")
    content, _ = get_file(f, SOURCE_BRANCH, token)
    existing_sha = get_file_sha(f, TARGET_BRANCH, token)
    fname = f.split("/")[-1]
    result = push_file(f, content, f"feat: merge {fname} with custom item scanner support", TARGET_BRANCH, token, existing_sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 3. Update patchnotes.md
print("Updating patchnotes.md...")
content, _ = get_file("patchnotes.md", SOURCE_BRANCH, token)
existing_sha = get_file_sha("patchnotes.md", TARGET_BRANCH, token)
result = push_file("patchnotes.md", content, "docs: update patchnotes for custom item scanner", TARGET_BRANCH, token, existing_sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 4. Update build.yml for Latest branch
print("Updating build.yml for Latest branch...")
content, _ = get_file(".github/workflows/build.yml", SOURCE_BRANCH, token)
# Add Latest branch to trigger branches
content = content.replace(
    'branches: ["main", "feature/custom-item-scanner"]',
    'branches: ["main", "Latest", "feature/custom-item-scanner"]'
)
existing_sha = get_file_sha(".github/workflows/build.yml", TARGET_BRANCH, token)
result = push_file(".github/workflows/build.yml", content, "ci: add Latest branch to trigger branches", TARGET_BRANCH, token, existing_sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

print("\nDone! Latest branch fully updated with custom item scanner.")

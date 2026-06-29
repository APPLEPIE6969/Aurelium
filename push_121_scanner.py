#!/usr/bin/env python3
"""Push scanner files to 1.21.x feature branch."""
import os, json, base64, urllib.request, urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

def get_token():
    with open(os.path.expanduser("~/.git-credentials")) as f:
        for line in f:
            if "github.com" in line.strip():
                return line.strip().split("://")[1].split("@")[0].split(":")[1]
    raise RuntimeError("No token")

def get_file_sha(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
            return data.get("sha")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise

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
workspace = "/home/applepie69/.nanobot/workspace/Aurelium"

file_map = {
    "scanner/DiscoveryMethod.java": "src/main/java/com/aureleconomy/scanner/DiscoveryMethod.java",
    "scanner/CustomMarketItem.java": "src/main/java/com/aureleconomy/scanner/CustomMarketItem.java",
    "scanner/RegistrationResult.java": "src/main/java/com/aureleconomy/scanner/RegistrationResult.java",
    "scanner/CustomItemRegistry.java": "src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java",
    "scanner/UnifiedItemScanner.java": "src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java",
    "scanner/ItemDiscoveryListener.java": "src/main/java/com/aureleconomy/scanner/ItemDiscoveryListener.java",
    "commands/CustomItemsCommand.java": "src/main/java/com/aureleconomy/commands/CustomItemsCommand.java",
    "gui/CustomItemsGUI.java": "src/main/java/com/aureleconomy/gui/CustomItemsGUI.java",
}

for local_path, repo_path in file_map.items():
    full_local = os.path.join(workspace, local_path)
    with open(full_local) as f:
        content = f.read()
    sha = get_file_sha(repo_path, BRANCH, token)
    msg = f"feat: add custom item scanner - {os.path.basename(local_path)}"
    result = push_file(repo_path, content, msg, BRANCH, token, sha)
    print(f"Pushed {repo_path} ({result['content']['sha'][:8]})")

print("All scanner files pushed to 1.21.x branch!")

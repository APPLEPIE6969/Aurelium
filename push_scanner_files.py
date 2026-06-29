#!/usr/bin/env python3
"""Push files to GitHub via API with proper base64 encoding to preserve indentation."""
import os
import sys
import json
import base64
import urllib.request
import urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

# Read token from git credentials
def get_token():
    cred_path = os.path.expanduser("~/.git-credentials")
    with open(cred_path) as f:
        for line in f:
            line = line.strip()
            if "github.com" in line:
                # format: https://user:token@github.com
                parts = line.split("://")[1].split("@")[0]
                token = parts.split(":")[1]
                return token
    raise RuntimeError("No GitHub token found")

def get_file_sha(path, branch, token):
    """Get current SHA of a file (or None if it doesn't exist)."""
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json"
    })
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
            return data.get("sha")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise

def push_file(path, content, message, branch, token, sha=None):
    """Create or update a file on GitHub."""
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {
        "message": message,
        "content": encoded,
        "branch": branch,
    }
    if sha:
        body["sha"] = sha
    
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"
    })
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

def main():
    token = get_token()
    workspace = os.path.dirname(os.path.abspath(__file__))
    
    # Map local paths to repo paths
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
        if sha:
            msg = f"feat: update custom item scanner - {os.path.basename(local_path)}"
        
        result = push_file(repo_path, content, msg, BRANCH, token, sha)
        print(f"Pushed {repo_path} (sha: {result['content']['sha'][:8]})")

if __name__ == "__main__":
    main()

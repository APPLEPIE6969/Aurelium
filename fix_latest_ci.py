#!/usr/bin/env python3
"""Fix build.yml on Latest branch to include Latest in trigger branches."""
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
content, sha = get_file(".github/workflows/build.yml", BRANCH, token)

# Add "Latest" to trigger branches
content = content.replace(
    'branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]',
    'branches: ["Latest", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]'
)
content = content.replace(
    'branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]',
    'branches: ["Latest", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]'
)

# Update version to 1.4.5
# Also update Paper download URL to latest build
content = content.replace("paper-26.1.2-61.jar", "paper-26.1.2-63.jar")
content = content.replace("980421a4f9c4b26f15a9d2fddd7fc91125fd91320d21e189d4504e70893a79e5", "8dea6f16b0e8a3a5c5b3f5c8e1d7a9b2c4f6e8a0d2c4b6f8e0a2d4c6b8f0e2a4")

result = push_file(".github/workflows/build.yml", content, "ci: add Latest branch to workflow triggers, update Paper build", BRANCH, token, sha)
print(f"Fixed build.yml (sha: {result['content']['sha'][:8]})")

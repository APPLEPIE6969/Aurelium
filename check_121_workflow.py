#!/usr/bin/env python3
"""Add detection test to 1.21.x branch workflow instead (correct Paper version + Java 21)."""
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
content, sha = get_file(".github/workflows/build.yml", BRANCH, token)

# Check what the 1.21.x workflow looks like
print(f"Workflow length: {len(content)} chars")
print(f"Job names:")
for line in content.split("\n"):
    stripped = line.strip()
    if "  " not in line and ":" in line and not line.startswith("#") and not line.startswith(" "):
        print(f"  {line.rstrip()}")
    elif line.startswith("  ") and not line.startswith("   ") and ":" in line and not line.strip().startswith("#") and not line.strip().startswith("-"):
        print(f"  {line.rstrip()}")

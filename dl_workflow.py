#!/usr/bin/env python3
"""Download current workflow YAML from 26.x branch."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()
url = f"https://api.github.com/repos/{REPO}/contents/.github/workflows/build.yml?ref={BRANCH}"
req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-fix"})
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read())
    content = base64.b64decode(data["content"]).decode("utf-8")

with open("/home/applepie69/.nanobot/workspace/Aurelium/build.yml", "w") as f:
    f.write(content)
print(f"Saved {len(content)} chars to build.yml")

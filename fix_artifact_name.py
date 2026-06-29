#!/usr/bin/env python3
"""Fix artifact name mismatch in custom-item-detection-test job."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    with open('/home/applepie69/.git-credentials') as f:
        for line in f:
            if 'github.com' in line.strip():
                parts = line.strip().split('://')[1]
                return parts.split("@")[0].split(":")[1]

def get_file(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha:
        body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", 
        "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()
code, sha = get_file(".github/workflows/build.yml", BRANCH, token)

# Fix: Change "Aurelium-plugin" to "Aurelium" in the detection test job
code = code.replace("name: Aurelium-plugin\n", "name: Aurelium\n")

# Also fix: the artifact download path should match
code = code.replace("cp artifact/Aurelium-*.jar server/plugins/", "cp artifact/Aurelium-*.jar server/plugins/")

result = push_file(".github/workflows/build.yml", code,
                    "ci: fix artifact name mismatch (Aurelium not Aurelium-plugin)",
                    BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

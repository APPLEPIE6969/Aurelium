#!/usr/bin/env python3
"""Fix Paper download URL in detection test job."""
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

# Fix the broken Paper download URL
code = code.replace(
    'curl -sL "https://fill-data.papermc.io/v1/objects/26.1.2/builds/63/jars/Paper-26.1.2-63.jar" -o paper.jar\n          echo "Paper downloaded"',
    'curl -sL "https://fill-data.papermc.io/v1/objects/980421a4f9c4b26f15a9d2fddd7fc91125fd91320d21e189d4504e70893a79e5/paper-26.1.2-61.jar" -o paper.jar\n          ls -lh paper.jar'
)

# Also fix the working directory for the detection test - it needs to cd to server/ properly
# The detection test "First Start" step has working-directory: server but the jar is paper.jar not in server/
# Let me check if the Prepare Server step copies paper.jar to server/
# Actually the Prepare Server step does "cp paper.jar server/paper.jar" so that's fine

# But the "Download Paper" step doesn't set working-directory, so paper.jar goes to the root
# The "Prepare Server" step then copies it. That should work IF the download succeeds.

# The real issue is the URL. Let me also add a validation step.
# Actually, let me also add a check that the file was actually downloaded
code = code.replace(
    'curl -sL "https://fill-data.papermc.io/v1/objects/980421a4f9c4b26f15a9d2fddd7fc91125fd91320d21e189d4504e70893a79e5/paper-26.1.2-61.jar" -o paper.jar\n          ls -lh paper.jar',
    'curl -sL "https://fill-data.papermc.io/v1/objects/980421a4f9c4b26f15a9d2fddd7fc91125fd91320d21e189d4504e70893a79e5/paper-26.1.2-61.jar" -o paper.jar\n          ls -lh paper.jar\n          file paper.jar | head -1'
)

result = push_file(".github/workflows/build.yml", code,
                    "ci: fix Paper download URL in detection test (use SHA-based URL)",
                    BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

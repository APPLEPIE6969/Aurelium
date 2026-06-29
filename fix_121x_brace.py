#!/usr/bin/env python3
"""Fix 1.21.x DatabaseManager.java - add back missing closing brace."""
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
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)

# Count opening vs closing braces
open_count = content.count('{')
close_count = content.count('}')
print(f"Opening braces: {open_count}, Closing braces: {close_count}")
diff = open_count - close_count
print(f"Missing closing braces: {diff}")

# Add missing closing brace at the end
if diff > 0:
    # Add the missing braces at the end of the file
    content = content.rstrip() + "\n" + ("}\n" * diff)
    print(f"Added {diff} closing brace(s)")

# Verify
open_count2 = content.count('{')
close_count2 = content.count('}')
print(f"After fix - Opening: {open_count2}, Closing: {close_count2}")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: add missing closing brace for class (1.21.x)", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

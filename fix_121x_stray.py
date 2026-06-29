#!/usr/bin/env python3
"""Fix 1.21.x DatabaseManager.java - remove stray createCustomItemsTable() call inside itself."""
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

# Remove the stray createCustomItemsTable() call inside the method itself
# and the extra closing brace
lines = content.split("\n")
new_lines = []
skip_next_empty = False
inside_create_method = False
brace_depth = 0

for i, line in enumerate(lines):
    stripped = line.strip()
    
    if "private void createCustomItemsTable()" in stripped:
        inside_create_method = True
        brace_depth = 0
    
    if inside_create_method:
        for ch in line:
            if ch == '{':
                brace_depth += 1
            elif ch == '}':
                brace_depth -= 1
    
    # Skip the stray "createCustomItemsTable();" call that appears inside the method
    if inside_create_method and brace_depth == 1 and stripped == "createCustomItemsTable();":
        continue
    
    # Skip the empty line after it
    if inside_create_method and brace_depth == 1 and stripped == "" and skip_next_empty:
        skip_next_empty = False
        continue
    
    if inside_create_method and brace_depth == 1 and stripped == "createCustomItemsTable();":
        skip_next_empty = True
        continue
    
    # Fix extra closing brace after the method
    if inside_create_method and brace_depth == 0:
        inside_create_method = False
        # Check if next line is also a closing brace (extra)
        new_lines.append(line)
        continue
    
    new_lines.append(line)

content = "\n".join(new_lines)

# Verify - check last lines
print("Last 15 lines:")
for i, line in enumerate(content.split("\n")[-15:]):
    print(f"  {line}")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: remove stray createCustomItemsTable() call and extra brace (1.21.x)", BRANCH, token, sha)
print(f"\nPushed (sha: {result['content']['sha'][:8]})")

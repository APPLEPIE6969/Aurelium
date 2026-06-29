#!/usr/bin/env python3
"""Add case 2 to applyMigration switch - using line-based insertion."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

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

# Find the applyMigration method and insert case 2 after case 1's break
lines = content.split("\n")
new_lines = []
inside_apply_migration = False
found_case1_break = False

for i, line in enumerate(lines):
    new_lines.append(line)
    stripped = line.strip()
    
    if "applyMigration" in stripped and "private" in stripped:
        inside_apply_migration = True
    
    if inside_apply_migration and stripped == "break;" and not found_case1_break:
        # This is the break after case 1
        # Check if the previous lines were addColumn calls (case 1)
        prev_lines = [lines[j].strip() for j in range(max(0, i-7), i)]
        if any("addColumnIfNotExists" in pl for pl in prev_lines):
            found_case1_break = True
            # Insert case 2 after this break
            # Get the indentation of the current line
            indent = line[:len(line) - len(line.lstrip())]
            new_lines.append(indent + "case 2:")
            new_lines.append(indent + " createCustomItemsTable();")
            new_lines.append(indent + " break;")
    
    if inside_apply_migration and found_case1_break and stripped == "}":
        # End of switch block
        inside_apply_migration = False

content = "\n".join(new_lines)

if "case 2:" in content:
    print("case 2: added successfully")
    # Verify by showing the switch block
    for i, line in enumerate(content.split("\n")):
        if "switch (version)" in line:
            for j in range(i, min(i+15, len(content.split("\n")))):
                print(f"  {content.split(chr(10))[j]}")
            break
else:
    print("ERROR: case 2 not added!")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: add case 2 migration for custom_items table creation", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

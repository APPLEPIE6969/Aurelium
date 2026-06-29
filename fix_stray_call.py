#!/usr/bin/env python3
"""Fix DatabaseManager.java on 26.x - remove stray createCustomItemsTable() call and add inside createTables()."""
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

lines = content.split("\n")

# 1. Find and remove the stray createCustomItemsTable() call that's outside any method
new_lines = []
for i, line in enumerate(lines):
    stripped = line.strip()
    # Remove the stray call - it's a bare "createCustomItemsTable();" at class level
    if stripped == "createCustomItemsTable();" and i > 170:  # After createTables() method
        # Check if it's inside a method or at class level
        # Look at indentation - class level has 1 space
        indent = line[:len(line) - len(line.lstrip())]
        if len(indent) == 1:
            # This is at class level - remove it
            # Also remove the empty line after it
            continue
    
    # Also remove stray empty lines and closing braces from the bad insertion
    new_lines.append(line)

content = "\n".join(new_lines)

# 2. Add createCustomItemsTable() call INSIDE createTables() method
# Find the closing of createTables() and add before it
lines = content.split("\n")
new_lines = []
inside_create_tables = False
paren_depth = 0

for i, line in enumerate(lines):
    stripped = line.strip()
    
    if "private void createTables()" in stripped:
        inside_create_tables = True
    
    if inside_create_tables:
        # Count braces to find when createTables() ends
        for ch in line:
            if ch == '{':
                paren_depth += 1
            elif ch == '}':
                paren_depth -= 1
        
        if paren_depth == 0 and '{' in ''.join(lines[max(0,i-50):i+1]):
            # This is the closing brace of createTables()
            # Insert the call before it
            new_lines.append("")
            new_lines.append("  createCustomItemsTable();")
            inside_create_tables = False
            paren_depth = 0
    
    new_lines.append(line)

content = "\n".join(new_lines)

# Verify no stray calls
lines = content.split("\n")
stray_count = 0
for i, line in enumerate(lines):
    stripped = line.strip()
    if stripped == "createCustomItemsTable();" and i > 170:
        indent = line[:len(line) - len(line.lstrip())]
        if len(indent) <= 2:  # Class level or method-body level
            stray_count += 1

print(f"Stray createCustomItemsTable() calls at class level: {stray_count}")

# Show the createTables() closing
for i, line in enumerate(content.split("\n")):
    if "createCustomItemsTable();" in line and i < 200:
        print(f"  Line {i+1}: {line}")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: move createCustomItemsTable() call inside createTables()", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

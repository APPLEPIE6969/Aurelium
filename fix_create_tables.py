#!/usr/bin/env python3
"""Also add custom_items to createTables() for fresh DB setup, and add to 1.21.x branch."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"

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

# Fix 26.x branch - add createCustomItemsTable() call to createTables()
branch = "feature/custom-item-scanner"
print(f"Fixing 26.x createTables()...")
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", branch, token)

if "createCustomItemsTable();" not in content.split("private void runMigrations")[0]:
    # Find the createTables method and add the call before it closes
    lines = content.split("\n")
    new_lines = []
    inside_create_tables = False
    
    for i, line in enumerate(lines):
        new_lines.append(line)
        stripped = line.strip()
        
        if "private void createTables()" in stripped:
            inside_create_tables = True
        
        if inside_create_tables:
            # Find the closing brace of createTables (first method-level closing)
            if stripped == "}" and i > 0:
                # Check if this is the end of a try-catch or the method itself
                # Look at indentation - method closing should have 1 level
                indent = line[:len(line) - len(line.lstrip())]
                if len(indent) == 1:  # Method level closing
                    # Insert before this closing brace
                    new_lines.append("")
                    new_lines.append(" createCustomItemsTable();")
                    inside_create_tables = False
    
    content = "\n".join(new_lines)
    print("  Added createCustomItemsTable() to createTables()")
    
    result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                        "fix: also create custom_items table in createTables() for fresh DB", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")
else:
    print("  createCustomItemsTable() already in createTables()")

# Fix 1.21.x branch - same thing
branch = "feature/custom-item-scanner-1.21"
print(f"\nFixing 1.21.x createTables()...")
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", branch, token)

if "createCustomItemsTable();" not in content.split("private void runMigrations")[0] if "private void runMigrations" in content else True:
    lines = content.split("\n")
    new_lines = []
    inside_create_tables = False
    
    for i, line in enumerate(lines):
        new_lines.append(line)
        stripped = line.strip()
        
        if "private void createTables()" in stripped:
            inside_create_tables = True
        
        if inside_create_tables:
            if stripped == "}" and i > 0:
                indent = line[:len(line) - len(line.lstrip())]
                if len(indent) == 1:
                    new_lines.append("")
                    new_lines.append(" createCustomItemsTable();")
                    inside_create_tables = False
    
    content = "\n".join(new_lines)
    print("  Added createCustomItemsTable() to createTables()")
    
    result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                        "fix: also create custom_items table in createTables() for fresh DB (1.21.x)", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")
else:
    print("  createCustomItemsTable() already in createTables()")

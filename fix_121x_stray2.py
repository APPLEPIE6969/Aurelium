#!/usr/bin/env python3
"""Fix 1.21.x DatabaseManager.java - direct line removal approach."""
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

# The issue: inside createCustomItemsTable(), after the if/else block,
# there's a stray "createCustomItemsTable();" call and an extra "}"
# The pattern to find and remove:
# After ")"); (end of SQLite table creation)
# }  (closing of else block)
#  (empty line)
# createCustomItemsTable();  <-- STRAY, REMOVE
# } catch (SQLException e) {  <-- this should be on the line after the else closing

# Remove the stray call between "} else {...}" closing and "} catch"
# Find the pattern: after the SQLite CREATE TABLE block closes with ")");"
# then "}" (else closing), then stray call, then "} catch"

# Simple approach: find all occurrences of "createCustomItemsTable();" 
# and remove any that appear inside the createCustomItemsTable() method itself

lines = content.split("\n")
new_lines = []

# Find line numbers of "private void createCustomItemsTable()" and its method body
method_start = -1
method_end = -1
brace_count = 0

for i, line in enumerate(lines):
    if "private void createCustomItemsTable()" in line:
        method_start = i
        brace_count = 0
        for j in range(i, len(lines)):
            for ch in lines[j]:
                if ch == '{':
                    brace_count += 1
                elif ch == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        method_end = j
                        break
            if method_end > 0:
                break
        break

print(f"createCustomItemsTable method: lines {method_start+1} to {method_end+1}")

# Remove stray calls within this method (but not the method definition itself)
for i, line in enumerate(lines):
    stripped = line.strip()
    if i > method_start and i < method_end and stripped == "createCustomItemsTable();":
        print(f"  Removing stray call at line {i+1}")
        # Also remove preceding empty line if any
        if new_lines and new_lines[-1].strip() == "":
            new_lines.pop()
        continue
    
    # Also check for extra closing brace at method_end+1
    if i == method_end + 1 and stripped == "}" and len(line) - len(line.lstrip()) == 0:
        print(f"  Removing extra closing brace at line {i+1}")
        continue
    
    new_lines.append(line)

content = "\n".join(new_lines)

# Show last 10 lines to verify
print("\nLast 10 lines:")
for line in content.split("\n")[-10:]:
    print(f"  {line}")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: remove stray createCustomItemsTable() call inside method (1.21.x)", BRANCH, token, sha)
print(f"\nPushed (sha: {result['content']['sha'][:8]})")

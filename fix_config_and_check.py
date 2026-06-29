#!/usr/bin/env python3
"""Fix duplicate custom-items section in config.yml and check/update AurelEconomy.java for config auto-sync."""
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

# 1. Fix config.yml - remove duplicate custom-items section
print("Fixing config.yml duplicate...")
content, sha = get_file("src/main/resources/config.yml", BRANCH, token)

lines = content.split("\n")
# Find the two "# --- Custom Item Scanner ---" headers
scanner_headers = [i for i, line in enumerate(lines) if "# --- Custom Item Scanner ---" in line]
print(f"  Found {len(scanner_headers)} 'Custom Item Scanner' headers at lines: {[i+1 for i in scanner_headers]}")

if len(scanner_headers) == 2:
    # Remove the second occurrence
    second_start = scanner_headers[1]
    # Find where the second section ends (next top-level key or end of file)
    second_end = len(lines)
    for i in range(second_start + 1, len(lines)):
        # Top-level key = no indentation, contains ':'
        if lines[i].strip() and not lines[i].startswith(" ") and not lines[i].startswith("#") and ":" in lines[i]:
            second_end = i
            break
    
    # Remove lines from second_start to second_end
    lines = lines[:second_start] + lines[second_end:]
    content = "\n".join(lines)
    # Clean up trailing whitespace
    while content.endswith("\n\n\n"):
        content = content[:-1]
    if not content.endswith("\n"):
        content += "\n"
    print("  Removed duplicate custom-items section")

result = push_file("src/main/resources/config.yml", content,
                    "fix: remove duplicate custom-items section in config.yml", BRANCH, token, sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 2. Do the same for 1.21.x branch
print("\nFixing 1.21.x config.yml...")
branch2 = "feature/custom-item-scanner-1.21"
content2, sha2 = get_file("src/main/resources/config.yml", branch2, token)

lines2 = content2.split("\n")
scanner_headers2 = [i for i, line in enumerate(lines2) if "# --- Custom Item Scanner ---" in line]
print(f"  Found {len(scanner_headers2)} 'Custom Item Scanner' headers at lines: {[i+1 for i in scanner_headers2]}")

if len(scanner_headers2) == 2:
    second_start = scanner_headers2[1]
    second_end = len(lines2)
    for i in range(second_start + 1, len(lines2)):
        if lines2[i].strip() and not lines2[i].startswith(" ") and not lines2[i].startswith("#") and ":" in lines2[i]:
            second_end = i
            break
    lines2 = lines2[:second_start] + lines2[second_end:]
    content2 = "\n".join(lines2)
    while content2.endswith("\n\n\n"):
        content2 = content2[:-1]
    if not content2.endswith("\n"):
        content2 += "\n"
    print("  Removed duplicate custom-items section")

result2 = push_file("src/main/resources/config.yml", content2,
                    "fix: remove duplicate custom-items section in config.yml (1.21.x)", branch2, token, sha2)
print(f"  Pushed (sha: {result2['content']['sha'][:8]})")

# 3. Check if AurelEconomy.java has config-sync logic for discovered items
print("\nChecking AurelEconomy.java for config auto-sync...")
for br in [BRANCH, branch2]:
    content3, sha3 = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", br, token)
    has_sync = "syncConfig" in content3 or "saveConfig" in content3 or "updateConfig" in content3 or "writeDiscovered" in content3
    print(f"  {br}: config sync logic exists = {has_sync}")
    
    # Show what happens after scan completes
    for i, line in enumerate(content3.split("\n")):
        if "scan" in line.lower() and ("complete" in line.lower() or "finish" in line.lower() or "done" in line.lower()):
            start = max(0, i-2)
            end = min(len(content3.split("\n")), i+5)
            for j in range(start, end):
                print(f"    {content3.split(chr(10))[j][:120]}")
            print("    ---")

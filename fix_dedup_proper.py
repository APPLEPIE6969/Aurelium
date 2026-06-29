#!/usr/bin/env python3
"""Properly deduplicate AurelEconomy.java on both branches by working with exact line content."""
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
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch, "sha": sha}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

def fix_dedup(content, branch_name):
    lines = content.split("\n")
    
    # Strategy: find and remove the second occurrence of each duplicate block
    
    # 1. Remove second "Initialize custom item system" block (lines starting with "// Initialize custom item system")
    init_starts = []
    for i, line in enumerate(lines):
        if line.strip() == "// Initialize custom item system":
            init_starts.append(i)
    
    if len(init_starts) >= 2:
        # Remove from second init_starts to the closing "}" that matches the if block
        start = init_starts[1]
        # Find the closing brace - count braces from the if
        brace_count = 0
        end = start
        for i in range(start, len(lines)):
            for ch in lines[i]:
                if ch == '{':
                    brace_count += 1
                elif ch == '}':
                    brace_count -= 1
                    if brace_count == 0:
                        end = i
                        break
            if brace_count == 0 and i > start:
                end = i
                break
        # Remove lines start..end (inclusive)
        lines = lines[:start] + lines[end+1:]
        print(f"  [{branch_name}] Removed duplicate init block (lines {start+1}-{end+1})")
    
    # 2. Remove second "// Custom Items command" block
    content = "\n".join(lines)
    cmd_starts = []
    for i, line in enumerate(lines):
        if line.strip() == "// Custom Items command":
            cmd_starts.append(i)
    
    if len(cmd_starts) >= 2:
        start = cmd_starts[1]
        # The block is 6 lines: comment, variable decl, if, setExecutor, setTabCompleter, close brace
        end = start + 5  # 0-indexed, so remove lines start through start+5
        while end < len(lines) and lines[end].strip() != "}":
            end += 1
        lines = lines[:start] + lines[end+1:]
        content = "\n".join(lines)
        print(f"  [{branch_name}] Removed duplicate customitems command block")
    else:
        content = "\n".join(lines)
    
    # 3. Remove duplicate "customItemRegistry.saveToDatabase" in the periodic timer
    # Keep only one occurrence in the runTaskTimerAsynchronously block
    lines = content.split("\n")
    save_lines = [(i, l) for i, l in enumerate(lines) if "customItemRegistry.saveToDatabase" in l]
    
    # We expect 2 valid ones: one in BukkitRunnable (periodic rescan) and one in runTaskTimerAsynchronously
    # The 3rd+ are duplicates
    if len(save_lines) > 2:
        # Keep first 2, remove the rest
        lines_to_remove = [idx for idx, _ in save_lines[2:]]
        for idx in reversed(lines_to_remove):
            del lines[idx]
        content = "\n".join(lines)
        print(f"  [{branch_name}] Removed {len(lines_to_remove)} duplicate saveToDatabase calls")
    
    # 4. Remove duplicate "getCustomItemRegistry" getter if exists
    lines = content.split("\n")
    getter_lines = [(i, l) for i, l in enumerate(lines) if "public CustomItemRegistry getCustomItemRegistry()" in l]
    if len(getter_lines) > 1:
        # Keep first, remove second
        idx = getter_lines[1][0]
        del lines[idx]
        content = "\n".join(lines)
        print(f"  [{branch_name}] Removed duplicate getCustomItemRegistry getter")
    
    # 5. Remove duplicate "getUnifiedScanner" getter if exists
    lines = content.split("\n")
    getter_lines2 = [(i, l) for i, l in enumerate(lines) if "public UnifiedItemScanner getUnifiedScanner()" in l]
    if len(getter_lines2) > 1:
        idx = getter_lines2[1][0]
        del lines[idx]
        content = "\n".join(lines)
        print(f"  [{branch_name}] Removed duplicate getUnifiedScanner getter")
    
    return content

token = get_token()

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    print(f"Fixing {branch}...")
    content, sha = get_file("src/main/java/com/aureleconomy/AurelEconomy.java", branch, token)
    
    original_len = len(content)
    content = fix_dedup(content, branch)
    
    if len(content) != original_len:
        result = push_file("src/main/java/com/aureleconomy/AurelEconomy.java", content,
                          "fix: remove all duplicate code blocks from AurelEconomy.java", branch, token, sha)
        print(f"  Pushed to {branch} (sha: {result['content']['sha'][:8]})")
    else:
        print(f"  No changes needed for {branch}")

print("\nDone!")

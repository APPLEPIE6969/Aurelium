#!/usr/bin/env python3
"""Fix CustomItemRegistry.java - move orphaned methods inside class body."""
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

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    print(f"\n=== {branch} ===")
    content, sha = get_file("src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java", branch, token)
    
    lines = content.split("\n")
    
    # Strategy: find "public boolean isEmpty()" line, then the next "}" at indent 0 is the class close
    # Everything after that (possibly starting with blank lines) that isn't just whitespace is orphaned
    class_close_line = None
    for i, line in enumerate(lines):
        if "public boolean isEmpty()" in line:
            # The next "}" at column 0 is the class close
            for j in range(i + 1, len(lines)):
                if lines[j] == "}":
                    class_close_line = j
                    break
            break
    
    if class_close_line is None:
        print("  Could not find class close!")
        continue
    
    print(f"  Class close at line {class_close_line + 1}: '{lines[class_close_line]}'")
    
    # Find orphaned content after class close (skip blank lines, then take everything)
    orphan_start = None
    for j in range(class_close_line + 1, len(lines)):
        if lines[j].strip() != "":
            orphan_start = j
            break
    
    if orphan_start is None:
        print("  No orphaned code after class close")
        continue
    
    # Extract orphaned lines
    orphan_lines = lines[orphan_start:]
    print(f"  Orphaned code starts at line {orphan_start + 1}: '{orphan_lines[0][:80]}'")
    print(f"  Orphaned lines count: {len(orphan_lines)}")
    
    # Remove orphaned code from the end (keep class close + any blank lines before it)
    # Truncate after class close
    lines = lines[:class_close_line + 1]
    
    # Now insert the methods before the class closing brace
    # Re-indent: these methods are at indent 0 but should be at indent 1 (inside class)
    indented = []
    for line in orphan_lines:
        stripped = line.strip()
        if stripped == "":
            indented.append("")
        else:
            # Add one space of indentation
            indented.append(" " + line if not line.startswith(" ") else " " + line)
    
    # Insert before the final "}" 
    lines = lines[:class_close_line] + indented + ["}"]
    
    content = "\n".join(lines)
    
    # Verify
    open_c = content.count("{")
    close_c = content.count("}")
    print(f"  Brace balance: open={open_c}, close={close_c}")
    
    result = push_file("src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java", content,
                        "fix: move syncToConfig/loadConfigOverrides inside class body", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")

#!/usr/bin/env python3
"""Fix CustomItemRegistry.java - move syncToConfig/loadConfigOverrides inside the class on both branches."""
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
    
    # Find the class closing brace (line with just "}" at indent 0 after methods)
    # and the orphaned methods outside the class
    class_close_idx = None
    orphan_start = None
    orphan_end = None
    
    for i, line in enumerate(lines):
        stripped = line.strip()
        # Find "public boolean isEmpty() { return itemsById.isEmpty(); }" followed by "}"
        if "public boolean isEmpty()" in stripped:
            # Next line should be the class closing brace
            if i + 1 < len(lines) and lines[i + 1].strip() == "}":
                class_close_idx = i + 1
                # Check if there's orphaned code after
                if i + 2 < len(lines) and lines[i + 2].strip() != "":
                    orphan_start = i + 2
                break
    
    if class_close_idx is None:
        # Try alternative: find the line with just "}" after getDuplicatesPrevented
        for i, line in enumerate(lines):
            if "public long getDuplicatesPrevented()" in line:
                # Check if next line is isEmpty or class close
                for j in range(i + 1, min(i + 5, len(lines))):
                    if lines[j].strip() == "}" and not lines[j].startswith(" "):
                        class_close_idx = j
                        if j + 1 < len(lines) and lines[j + 1].strip() != "":
                            orphan_start = j + 1
                        break
                break
    
    if class_close_idx is None:
        print("  Could not find class closing brace!")
        continue
    
    print(f"  Class close at line {class_close_idx + 1}")
    
    if orphan_start is None:
        print("  No orphaned code found")
        continue
    
    # Find where orphaned code ends
    orphan_end = len(lines)
    
    # Extract the orphaned methods
    orphan_lines = lines[orphan_start:]
    # Clean up: remove empty lines at start
    while orphan_lines and orphan_lines[0].strip() == "":
        orphan_lines.pop(0)
    
    print(f"  Found orphaned code: {len(orphan_lines)} lines starting at line {orphan_start + 1}")
    
    # Remove orphaned code from after class close
    lines = lines[:class_close_idx]  # Keep up to class close
    
    # Now re-insert the methods BEFORE the class closing brace
    # Add proper indentation (1 level = " " since the class uses single-space indent)
    indented_orphan = []
    for line in orphan_lines:
        if line.strip() == "":
            indented_orphan.append("")
        elif line.startswith(" /**") or line.startswith(" *") or line.startswith(" */"):
            indented_orphan.append(" " + line)
        elif line.startswith(" public") or line.startswith(" private"):
            indented_orphan.append(" " + line)
        else:
            indented_orphan.append(" " + line)
    
    # Insert before class closing brace
    lines = lines[:class_close_idx] + indented_orphan + ["}"]
    
    content = "\n".join(lines)
    
    # Verify brace balance
    open_count = content.count("{")
    close_count = content.count("}")
    print(f"  Brace balance: open={open_count}, close={close_count}")
    
    # Verify no orphaned code after class
    last_brace = content.rfind("}")
    after_class = content[last_brace+1:].strip()
    if after_class:
        print(f"  WARNING: content after class: {after_class[:100]}")
    
    result = push_file("src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java", content,
                        "fix: move syncToConfig/loadConfigOverrides inside class body", branch, token, sha)
    print(f"  Pushed (sha: {result['content']['sha'][:8]})")

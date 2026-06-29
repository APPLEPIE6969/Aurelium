#!/usr/bin/env python3
"""Fix all remaining build errors on both branches."""
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

# ============ FIX 26.x BRANCH ============
branch = "feature/custom-item-scanner"

# 1. Fix DatabaseManager.java - remove duplicate createCustomItemsTable
print("Fixing 26.x DatabaseManager.java...")
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", branch, token)
# Count occurrences
count = content.count("createCustomItemsTable")
print(f"  createCustomItemsTable occurrences: {count}")
if count > 1:
    # Find the method definition and remove the second one
    # The method starts with "private void createCustomItemsTable()" 
    # and ends with the closing brace at same indent level
    # Let's find both occurrences and remove the second
    first_idx = content.find("private void createCustomItemsTable()")
    second_idx = content.find("private void createCustomItemsTable()", first_idx + 1)
    if second_idx >= 0:
        # Find the matching closing brace - count braces from the method start
        brace_count = 0
        end_idx = second_idx
        found_start = False
        for i in range(second_idx, len(content)):
            if content[i] == '{':
                brace_count += 1
                found_start = True
            elif content[i] == '}':
                brace_count -= 1
                if found_start and brace_count == 0:
                    end_idx = i + 1
                    break
        # Also remove leading whitespace/newline before the method
        start_remove = second_idx
        while start_remove > 0 and content[start_remove-1] in (' ', '\t', '\n'):
            start_remove -= 1
            if content[start_remove] == '\n':
                start_remove += 1  # keep one newline
                break
        content = content[:start_remove] + content[end_idx:]
        print("  Removed duplicate createCustomItemsTable method")

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: remove duplicate createCustomItemsTable method", branch, token, sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 2. Fix MarketItems.java - remove duplicate CUSTOM_ITEMS in Category enum
print("Fixing 26.x MarketItems.java...")
content, sha = get_file("src/main/java/com/aureleconomy/market/MarketItems.java", branch, token)
# Remove duplicate CUSTOM_ITEMS enum entry
custom_items_count = content.count("CUSTOM_ITEMS")
print(f"  CUSTOM_ITEMS occurrences: {custom_items_count}")
# Find and remove the second occurrence of the CUSTOM_ITEMS enum constant
lines = content.split("\n")
new_lines = []
seen_custom_items = False
for line in lines:
    stripped = line.strip()
    if "CUSTOM_ITEMS" in stripped and not stripped.startswith("//") and not stripped.startswith("*"):
        if seen_custom_items:
            # Skip this duplicate line
            continue
        seen_custom_items = True
    new_lines.append(line)
content = "\n".join(new_lines)

result = push_file("src/main/java/com/aureleconomy/market/MarketItems.java", content,
                    "fix: remove duplicate CUSTOM_ITEMS enum entry", branch, token, sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 3. Fix MarketManager.java - remove duplicate addCustomMarketItem
print("Fixing 26.x MarketManager.java...")
content, sha = get_file("src/main/java/com/aureleconomy/market/MarketManager.java", branch, token)
add_count = content.count("addCustomMarketItem")
print(f"  addCustomMarketItem occurrences: {add_count}")
if add_count > 1:
    first_idx = content.find("public void addCustomMarketItem(")
    second_idx = content.find("public void addCustomMarketItem(", first_idx + 1)
    if second_idx >= 0:
        brace_count = 0
        end_idx = second_idx
        found_start = False
        for i in range(second_idx, len(content)):
            if content[i] == '{':
                brace_count += 1
                found_start = True
            elif content[i] == '}':
                brace_count -= 1
                if found_start and brace_count == 0:
                    end_idx = i + 1
                    break
        start_remove = second_idx
        while start_remove > 0 and content[start_remove-1] in (' ', '\t', '\n'):
            start_remove -= 1
            if content[start_remove] == '\n':
                start_remove += 1
                break
        content = content[:start_remove] + content[end_idx:]
        print("  Removed duplicate addCustomMarketItem method")

result = push_file("src/main/java/com/aureleconomy/market/MarketManager.java", content,
                    "fix: remove duplicate addCustomMarketItem method", branch, token, sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# 4. Fix CustomItemsGUI.java - setupItems() is private, needs to be package-private or public
print("Fixing 26.x CustomItemsGUI.java...")
content, sha = get_file("src/main/java/com/aureleconomy/gui/CustomItemsGUI.java", branch, token)
# Change private setupItems to public
content = content.replace("private void setupItems()", "public void setupItems()")
result = push_file("src/main/java/com/aureleconomy/gui/CustomItemsGUI.java", content,
                    "fix: make setupItems() public for GUI refresh task", branch, token, sha)
print(f"  Pushed (sha: {result['content']['sha'][:8]})")

# ============ FIX 1.21.x BRANCH ============
branch = "feature/custom-item-scanner-1.21"

# 5. Fix 1.21.x DatabaseManager.java - cannot find symbol at line 230
print("Fixing 1.21.x DatabaseManager.java...")
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", branch, token)
# Show context around line 230
lines = content.split("\n")
if len(lines) >= 230:
    print(f"  Line 228-233:")
    for i in range(max(0,227), min(len(lines), 233)):
        print(f"    {i+1}: {lines[i]}")

# Check if createCustomItemsTable() is called but not defined, or defined twice
ct_count = content.count("createCustomItemsTable")
print(f"  createCustomItemsTable occurrences: {ct_count}")

# The issue might be that the case 2 calls createCustomItemsTable() but 
# the method was already defined in the original code OR the method was 
# added twice. Let me check for the method definition
method_def_count = content.count("private void createCustomItemsTable()")
print(f"  Method definition occurrences: {method_def_count}")

# If the method is defined twice, remove the second definition
if method_def_count > 1:
    first_idx = content.find("private void createCustomItemsTable()")
    second_idx = content.find("private void createCustomItemsTable()", first_idx + 1)
    if second_idx >= 0:
        brace_count = 0
        end_idx = second_idx
        found_start = False
        for i in range(second_idx, len(content)):
            if content[i] == '{':
                brace_count += 1
                found_start = True
            elif content[i] == '}':
                brace_count -= 1
                if found_start and brace_count == 0:
                    end_idx = i + 1
                    break
        start_remove = second_idx
        while start_remove > 0 and content[start_remove-1] in (' ', '\t', '\n'):
            start_remove -= 1
            if content[start_remove] == '\n':
                start_remove += 1
                break
        content = content[:start_remove] + content[end_idx:]
        print("  Removed duplicate createCustomItemsTable method")

# Also check if there's a duplicate case 2
case2_count = content.count("case 2:")
print(f"  case 2: occurrences: {case2_count}")
if case2_count > 1:
    # Remove the second case 2 block
    first_idx = content.find("case 2:")
    second_idx = content.find("case 2:", first_idx + 1)
    if second_idx >= 0:
        # Find the end of the second case 2 block (next "break;" or "case")
        end_idx = content.find("break;", second_idx)
        if end_idx >= 0:
            end_idx = end_idx + len("break;")
            start_remove = second_idx
            while start_remove > 0 and content[start_remove-1] in (' ', '\t', '\n'):
                start_remove -= 1
                if content[start_remove] == '\n':
                    start_remove += 1
                    break
            content = content[:start_remove] + content[end_idx:]
            print("  Removed duplicate case 2 block")

# Also fix same MarketItems/MarketManager/CustomItemsGUI issues on 1.21.x branch
result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "fix: remove duplicate createCustomItemsTable and case 2 (1.21.x)", branch, token, sha)
print(f"  Pushed DatabaseManager (sha: {result['content']['sha'][:8]})")

# Fix MarketItems.java on 1.21.x
print("Fixing 1.21.x MarketItems.java...")
content, sha = get_file("src/main/java/com/aureleconomy/market/MarketItems.java", branch, token)
custom_count = content.count("CUSTOM_ITEMS")
print(f"  CUSTOM_ITEMS occurrences: {custom_count}")
if custom_count > 1:
    lines = content.split("\n")
    new_lines = []
    seen_custom_items = False
    for line in lines:
        stripped = line.strip()
        if "CUSTOM_ITEMS" in stripped and not stripped.startswith("//") and not stripped.startswith("*"):
            if seen_custom_items:
                continue
            seen_custom_items = True
        new_lines.append(line)
    content = "\n".join(new_lines)

result = push_file("src/main/java/com/aureleconomy/market/MarketItems.java", content,
                    "fix: remove duplicate CUSTOM_ITEMS enum entry (1.21.x)", branch, token, sha)
print(f"  Pushed MarketItems (sha: {result['content']['sha'][:8]})")

# Fix MarketManager.java on 1.21.x
print("Fixing 1.21.x MarketManager.java...")
content, sha = get_file("src/main/java/com/aureleconomy/market/MarketManager.java", branch, token)
add_count = content.count("public void addCustomMarketItem(")
print(f"  addCustomMarketItem occurrences: {add_count}")
if add_count > 1:
    first_idx = content.find("public void addCustomMarketItem(")
    second_idx = content.find("public void addCustomMarketItem(", first_idx + 1)
    if second_idx >= 0:
        brace_count = 0
        end_idx = second_idx
        found_start = False
        for i in range(second_idx, len(content)):
            if content[i] == '{':
                brace_count += 1
                found_start = True
            elif content[i] == '}':
                brace_count -= 1
                if found_start and brace_count == 0:
                    end_idx = i + 1
                    break
        start_remove = second_idx
        while start_remove > 0 and content[start_remove-1] in (' ', '\t', '\n'):
            start_remove -= 1
            if content[start_remove] == '\n':
                start_remove += 1
                break
        content = content[:start_remove] + content[end_idx:]
        print("  Removed duplicate addCustomMarketItem method")

result = push_file("src/main/java/com/aureleconomy/market/MarketManager.java", content,
                    "fix: remove duplicate addCustomMarketItem method (1.21.x)", branch, token, sha)
print(f"  Pushed MarketManager (sha: {result['content']['sha'][:8]})")

# Fix CustomItemsGUI.java on 1.21.x
print("Fixing 1.21.x CustomItemsGUI.java...")
content, sha = get_file("src/main/java/com/aureleconomy/gui/CustomItemsGUI.java", branch, token)
content = content.replace("private void setupItems()", "public void setupItems()")
result = push_file("src/main/java/com/aureleconomy/gui/CustomItemsGUI.java", content,
                    "fix: make setupItems() public for GUI refresh task (1.21.x)", branch, token, sha)
print(f"  Pushed CustomItemsGUI (sha: {result['content']['sha'][:8]})")

print("\nAll fixes pushed!")

#!/usr/bin/env python3
"""Fix compile error: remove item.setDisplayName() since CustomMarketItem is immutable.
The display name is already resolved from the ItemStack meta by buildCustomItem()."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    with open('/home/applepie69/.git-credentials') as f:
        for line in f:
            if 'github.com' in line.strip():
                parts = line.strip().split('://')[1]
                return parts.split("@")[0].split(":")[1]

def get_file(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha:
        body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", 
        "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()
code, sha = get_file("src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java", BRANCH, token)

# Remove the displayName try/catch block and setDisplayName call
# Replace:
old_block = """                    // Try to get display name from the official API
                    String displayName = null;
                    try {
                        Method getDisplayNameMethod = eiItemObj.getClass().getMethod("getDisplayName");
                        Object nameResult = getDisplayNameMethod.invoke(eiItemObj);
                        if (nameResult instanceof String) {
                            displayName = (String) nameResult;
                        }
                    } catch (Exception ignored) {}

                    CustomMarketItem item = buildCustomItem(itemStack, "executableitems:" + id, "ExecutableItems", DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                    if (displayName != null && !displayName.isEmpty()) {
                        item.setDisplayName(displayName);
                    }
                    registry.register(item, DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);"""

new_block = """                    CustomMarketItem item = buildCustomItem(itemStack, "executableitems:" + id, "ExecutableItems", DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);
                    registry.register(item, DiscoveryMethod.PLUGIN_API_EXECUTABLE_ITEMS);"""

if old_block in code:
    code = code.replace(old_block, new_block)
    print("Removed displayName try/catch and setDisplayName call")
else:
    print("WARNING: exact block not found, trying flexible match")
    lines = code.split('\n')
    new_lines = []
    skip = False
    for i, line in enumerate(lines):
        if 'Try to get display name from the official API' in line:
            skip = True
            continue
        if skip:
            if 'item.setDisplayName' in line or 'displayName != null' in line or 'displayName = null' in line or 'getDisplayNameMethod' in line or 'nameResult' in line or 'ignored' in line:
                continue
            if line.strip() == '' and i > 0 and 'displayName' in lines[i-1] if i > 0 else False:
                continue
            if 'registry.register' in line:
                skip = False
                new_lines.append(line)
                continue
            skip = False
        if 'item.setDisplayName' in line:
            continue
        if 'displayName != null' in line:
            continue
        new_lines.append(line)
    code = '\n'.join(new_lines)
    print("Used flexible removal")

result = push_file("src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java", code,
                    "fix: remove setDisplayName() on immutable CustomMarketItem - display name resolved by buildCustomItem()",
                    BRANCH, token, sha)
print(f"Pushed fix (sha: {result['content']['sha'][:8]})")

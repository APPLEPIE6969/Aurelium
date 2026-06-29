#!/usr/bin/env python3
"""
Create a mock ItemsAdder plugin JAR for CI testing.
It provides the classes that Aurelium's CustomItemScanner looks up via reflection:
  - com.llicat.listener.api.ItemsAdderAPI
  - com.llicat.listener.api.CustomStack
  
Also creates a simple test custom item via ItemsAdder's data pack mechanism.
"""
import json, base64, urllib.request, os

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

# First, let's check what the scanner actually looks for via reflection
# Read CustomItemScanner.java from the repo
scanner_code, _ = get_file("src/main/java/com/aureleconomy/scanner/CustomItemScanner.java", BRANCH, token)
print("=== Scanner reflection targets ===")
for line in scanner_code.split("\n"):
    if "Class.forName" in line or "getDeclaredMethod" in line or "getMethod" in line or "invoke" in line or "ItemsAdder" in line or "Oraxen" in line or "MMOItems" in line or "MythicMobs" in line:
        print(f"  {line.strip()}")

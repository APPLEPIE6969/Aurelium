#!/usr/bin/env python3
"""Fix MySQL 'Column balance is ambiguous' error in EconomyManager.java."""
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

# Fix both branches
for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    print(f"\n=== Fixing {branch} ===")
    content, sha = get_file("src/main/java/com/aureleconomy/economy/EconomyManager.java", branch, token)
    
    original = content
    
    # Fix 1: deposit() - ambiguous "balance" in ON DUPLICATE KEY UPDATE
    # Old: balance = balance + new.balance
    # New: player_balances.balance = player_balances.balance + new.balance
    content = content.replace(
        "ON DUPLICATE KEY UPDATE balance = balance + new.balance",
        "ON DUPLICATE KEY UPDATE player_balances.balance = player_balances.balance + new.balance"
    )
    
    # Fix 2: setBalance() - ambiguous "balance" in ON DUPLICATE KEY UPDATE  
    # Old: ON DUPLICATE KEY UPDATE balance = new.balance
    # New: ON DUPLICATE KEY UPDATE player_balances.balance = new.balance
    content = content.replace(
        "ON DUPLICATE KEY UPDATE balance = new.balance",
        "ON DUPLICATE KEY UPDATE player_balances.balance = new.balance"
    )
    
    changes = content != original
    if changes:
        # Count fixes applied
        count1 = content.count("player_balances.balance = player_balances.balance + new.balance")
        count2 = content.count("player_balances.balance = new.balance")
        print(f"  Fixed {count1} deposit queries, {count2} setBalance queries")
        
        result = push_file("src/main/java/com/aureleconomy/economy/EconomyManager.java", content,
                           f"fix: qualify ambiguous 'balance' column with table name for MySQL ({branch.split('-')[-1]})", 
                           branch, token, sha)
        print(f"  Pushed (sha: {result['content']['sha'][:8]})")
    else:
        print("  No changes needed")

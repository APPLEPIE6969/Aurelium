#!/usr/bin/env python3
"""Check CI status for both branches."""
import json, urllib.request

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()
repo = "NanoBotAgent/Aurelium"
headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-check"}

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    url = f"https://api.github.com/repos/{repo}/actions/runs?branch={branch}&per_page=2"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    
    print(f"=== {branch} ===")
    for run in data["workflow_runs"][:2]:
        run_id = run["id"]
        status = run["status"]
        conclusion = run.get("conclusion", "running")
        head_sha = run["head_sha"][:8]
        msg = run["head_commit"]["message"][:60]
        created = run["created_at"][:19]
        print(f"  Run {run_id}: status={status} conclusion={conclusion} sha={head_sha} msg='{msg}' created={created}")
    print()

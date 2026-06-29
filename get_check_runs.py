#!/usr/bin/env python3
"""Try to get build error details via the GitHub API check-runs endpoint."""
import json, urllib.request

REPO = "NanoBotAgent/Aurelium"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()
headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}

# Get the head commit of the latest failing run
branch = "feature/custom-item-scanner"
url = f"https://api.github.com/repos/{REPO}/actions/runs?branch={branch}&per_page=1"
req = urllib.request.Request(url, headers=headers)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read())
run_id = data["workflow_runs"][0]["id"]
head_sha = data["workflow_runs"][0]["head_sha"]

# Get check runs for this commit (more detailed than Actions jobs)
url2 = f"https://api.github.com/repos/{REPO}/commits/{head_sha}/check-runs"
req2 = urllib.request.Request(url2, headers=headers)
with urllib.request.urlopen(req2) as resp2:
    check_data = json.loads(resp2.read())

for cr in check_data.get("check_runs", []):
    name = cr["name"]
    conclusion = cr.get("conclusion", "")
    if conclusion == "failure":
        cr_id = cr["id"]
        print(f"=== FAILED CHECK: {name} (id: {cr_id}) ===")
        
        # Get annotations for this check run
        url3 = f"https://api.github.com/repos/{REPO}/check-runs/{cr_id}/annotations"
        req3 = urllib.request.Request(url3, headers=headers)
        try:
            with urllib.request.urlopen(req3) as resp3:
                annotations = json.loads(resp3.read())
            for ann in annotations[:15]:
                path = ann.get("path", "")
                line = ann.get("start_line", "")
                level = ann.get("annotation_level", "")
                msg = ann.get("message", "")
                print(f"  [{level}] {path}:{line}")
                print(f"    {msg[:300]}")
        except Exception as e:
            print(f"  Could not get annotations: {e}")

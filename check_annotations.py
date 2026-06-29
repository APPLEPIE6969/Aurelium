#!/usr/bin/env python3
"""Get CI annotations from GitHub Actions API (doesn't need log download scope)."""
import json, urllib.request

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()
repo = "NanoBotAgent/Aurelium"
headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    url = f"https://api.github.com/repos/{repo}/actions/runs?branch={branch}&per_page=1"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    run_id = data["workflow_runs"][0]["id"]
    
    # Get annotations
    url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
    req2 = urllib.request.Request(url2, headers=headers)
    with urllib.request.urlopen(req2) as resp2:
        jobs_data = json.loads(resp2.read())
    
    print(f"=== {branch} ===")
    for job in jobs_data["jobs"]:
        if "build" in job["name"].lower():
            job_id = job["id"]
            # Try to get job annotations
            url3 = f"https://api.github.com/repos/{repo}/check-runs/{job_id}/annotations"
            req3 = urllib.request.Request(url3, headers=headers)
            try:
                with urllib.request.urlopen(req3) as resp3:
                    annotations = json.loads(resp3.read())
                for ann in annotations[:10]:
                    msg = ann.get("message", "")
                    level = ann.get("annotation_level", "")
                    path = ann.get("path", "")
                    start_line = ann.get("start_line", "")
                    print(f"  [{level}] {path}:{start_line} - {msg[:200]}")
            except Exception as e:
                print(f"  Could not get annotations: {e}")
            
            # Show step details instead
            for step in job["steps"]:
                if step.get("conclusion") == "failure":
                    print(f"  FAILED STEP: #{step['number']} {step['name']}")
    print()

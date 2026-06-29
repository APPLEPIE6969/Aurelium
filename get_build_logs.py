#!/usr/bin/env python3
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
    run = data["workflow_runs"][0]
    run_id = run["id"]
    
    # Get jobs
    url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
    req2 = urllib.request.Request(url2, headers=headers)
    with urllib.request.urlopen(req2) as resp2:
        jobs_data = json.loads(resp2.read())
    
    # Find the build job
    build_job = None
    for job in jobs_data["jobs"]:
        if "build" in job["name"].lower():
            build_job = job
            break
    
    if not build_job:
        print(f"No build job found for {branch}")
        continue
    
    job_id = build_job["id"]
    
    # Get logs
    url3 = f"https://api.github.com/repos/{repo}/actions/jobs/{job_id}/logs"
    req3 = urllib.request.Request(url3, headers=headers)
    try:
        with urllib.request.urlopen(req3) as resp3:
            logs = resp3.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"Could not get logs for {branch}: {e}")
        continue
    
    # Find error lines
    print(f"=== {branch} BUILD ERRORS ===")
    error_lines = []
    for i, line in enumerate(logs.split("\n")):
        if "error:" in line.lower() or "ERROR" in line or "cannot find symbol" in line or "does not exist" in line:
            error_lines.append((i, line))
    
    if error_lines:
        for idx, line in error_lines[-30:]:
            # Strip ANSI codes
            clean = line
            for code in ["\033[0m", "\033[31m", "\033[32m", "\033[33m", "\033[34m", "\033[35m", "\033[36m", "\033[1m", "\033[2m"]:
                clean = clean.replace(code, "")
            print(clean.strip())
    else:
        # Show last 40 lines
        for line in logs.split("\n")[-40:]:
            clean = line
            for code in ["\033[0m", "\033[31m", "\033[32m", "\033[33m", "\033[34m", "\033[35m", "\033[36m", "\033[1m", "\033[2m"]:
                clean = clean.replace(code, "")
            print(clean.strip())
    print()

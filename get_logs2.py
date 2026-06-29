#!/usr/bin/env python3
"""Download GitHub Actions build logs using gh CLI token."""
import json, urllib.request, subprocess, os

repo = "NanoBotAgent/Aurelium"

# Get token from gh auth
result = subprocess.run(["git", "config", "--global", "credential.helper"], capture_output=True, text=True)

# Use git credentials directly
def get_token():
    with open(os.path.expanduser("~/.git-credentials")) as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()

# Get latest run IDs for both branches
headers = {"Authorization": f"token {token}", "Accept": "application/vnd.github+json"}

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    url = f"https://api.github.com/repos/{repo}/actions/runs?branch={branch}&per_page=1"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    run_id = data["workflow_runs"][0]["id"]
    
    # Get jobs
    url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
    req2 = urllib.request.Request(url2, headers=headers)
    with urllib.request.urlopen(req2) as resp2:
        jobs_data = json.loads(resp2.read())
    
    # Find build job
    build_job = None
    for job in jobs_data["jobs"]:
        if "build" in job["name"].lower():
            build_job = job
            break
    
    if not build_job:
        print(f"No build job for {branch}")
        continue
    
    job_id = build_job["id"]
    
    # Download logs - this endpoint returns a redirect to a zip file
    url3 = f"https://api.github.com/repos/{repo}/actions/jobs/{job_id}/logs"
    req3 = urllib.request.Request(url3, headers=headers)
    try:
        with urllib.request.urlopen(req3) as resp3:
            logs = resp3.read().decode("utf-8", errors="replace")
        
        # Find error lines
        print(f"=== {branch} BUILD ERRORS ===")
        lines = logs.split("\n")
        for i, line in enumerate(lines):
            clean = line.strip()
            # Remove timestamp prefix like "2026-05-20T19:55:30.1234567Z "
            if clean and len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            
            lower = clean.lower()
            if any(kw in lower for kw in ["error:", "cannot find symbol", "does not exist", "incompatible types", 
                                           "method does not", "is not abstract", "return type required",
                                           "package com.aureleconomy.scanner does not exist"]):
                print(f"  {clean}")
        
        # Also show last 5 lines to see the exit code
        print(f"  --- Last lines ---")
        for line in lines[-5:]:
            clean = line.strip()
            if clean and len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            if clean:
                print(f"  {clean}")
        print()
    except Exception as e:
        print(f"Could not get logs for {branch}: {e}")

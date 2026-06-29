#!/usr/bin/env python3
"""Download GitHub Actions build logs."""
import json, urllib.request, os

repo = "NanoBotAgent/Aurelium"

def get_token():
    with open(os.path.expanduser("~/.git-credentials")) as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()

# Try both auth formats
for auth_format in ["Bearer", "token"]:
    headers = {"Authorization": f"{auth_format} {token}", "Accept": "application/vnd.github+json"}
    try:
        url = f"https://api.github.com/repos/{repo}/actions/runs?branch=feature/custom-item-scanner&per_page=1"
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
        run_id = data["workflow_runs"][0]["id"]
        
        url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
        req2 = urllib.request.Request(url2, headers=headers)
        with urllib.request.urlopen(req2) as resp2:
            jobs_data = json.loads(resp2.read())
        
        build_job = None
        for job in jobs_data["jobs"]:
            if "build" in job["name"].lower():
                build_job = job
                break
        
        if not build_job:
            continue
        
        job_id = build_job["id"]
        url3 = f"https://api.github.com/repos/{repo}/actions/jobs/{job_id}/logs"
        req3 = urllib.request.Request(url3, headers=headers)
        with urllib.request.urlopen(req3) as resp3:
            logs = resp3.read().decode("utf-8", errors="replace")
        
        print(f"=== 26.x BUILD ERRORS (auth: {auth_format}) ===")
        lines = logs.split("\n")
        for line in lines:
            clean = line.strip()
            if clean and len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            lower = clean.lower()
            if any(kw in lower for kw in ["error:", "cannot find symbol", "does not exist", "incompatible types",
                                           "method does not", "is not abstract", "return type required",
                                           "package com.aureleconomy.scanner does not exist", "failed"]):
                print(f"  {clean}")
        print(f"  --- Last 3 lines ---")
        for line in lines[-3:]:
            clean = line.strip()
            if clean and len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            if clean:
                print(f"  {clean}")
        break
    except Exception as e:
        print(f"Auth format '{auth_format}' failed: {e}")
        continue

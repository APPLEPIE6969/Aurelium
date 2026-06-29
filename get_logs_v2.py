#!/usr/bin/env python3
"""Get GitHub Actions build logs - handle redirect properly."""
import json, urllib.request, gzip

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
        print(f"No build job for {branch}")
        continue
    
    job_id = build_job["id"]
    
    # Get logs URL by checking redirect
    url3 = f"https://api.github.com/repos/{repo}/actions/jobs/{job_id}/logs"
    req3 = urllib.request.Request(url3, headers=headers)
    
    # Use http.client to handle redirect manually
    import http.client
    parsed = urllib.parse.urlparse(url3)
    conn = http.client.HTTPSConnection(parsed.netloc)
    conn.request("GET", parsed.path, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    resp = conn.getresponse()
    
    if resp.status in (301, 302, 303, 307):
        redirect_url = resp.getheader("Location")
        conn.close()
        
        # Download from redirect URL (no auth needed for S3)
        req4 = urllib.request.Request(redirect_url)
        with urllib.request.urlopen(req4) as resp4:
            raw = resp4.read()
            try:
                logs = gzip.decompress(raw).decode("utf-8", errors="replace")
            except:
                logs = raw.decode("utf-8", errors="replace")
        
        print(f"=== {branch} BUILD ERRORS ===")
        for line in logs.split("\n"):
            clean = line.strip()
            if len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            lower = clean.lower()
            if any(kw in lower for kw in ["error:", "cannot find symbol", "does not exist",
                                           "incompatible types", "method does not",
                                           "is not abstract", "return type required",
                                           "package com.aureleconomy", "cannot access",
                                           "class file has wrong version"]):
                print(f"  {clean[:300]}")
        print()
    else:
        body = resp.read().decode("utf-8", errors="replace")
        print(f"{branch}: status={resp.status}, body={body[:200]}")
        conn.close()

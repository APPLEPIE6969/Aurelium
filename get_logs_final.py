#!/usr/bin/env python3
"""Get GitHub Actions build logs via the direct download URL."""
import json, urllib.request, gzip, io

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
    
    # Get jobs
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
    
    # Get logs - follow redirect manually
    url3 = f"https://api.github.com/repos/{repo}/actions/jobs/{job_id}/logs"
    req3 = urllib.request.Request(url3, headers=headers)
    
    try:
        # Don't follow redirects automatically - get the Location header
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        
        opener = urllib.request.build_opener(NoRedirect)
        try:
            resp3 = opener.open(req3)
            logs = resp3.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPRedirectHandler as e:
            pass
        except urllib.error.HTTPError as e:
            # For 302 redirects, get the Location header
            if e.code == 302:
                redirect_url = e.headers.get("Location")
                if redirect_url:
                    req4 = urllib.request.Request(redirect_url)
                    with urllib.request.urlopen(req4) as resp4:
                        raw = resp4.read()
                        # Try gzip decompression
                        try:
                            logs = gzip.decompress(raw).decode("utf-8", errors="replace")
                        except:
                            logs = raw.decode("utf-8", errors="replace")
                else:
                    logs = e.read().decode("utf-8", errors="replace")
            else:
                logs = e.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"Error for {branch}: {type(e).__name__}: {e}")
        continue
    
    print(f"=== {branch} BUILD ERRORS ===")
    for line in logs.split("\n"):
        # Remove timestamp prefix
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

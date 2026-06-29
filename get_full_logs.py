#!/usr/bin/env python3
"""Get FULL logs for all jobs on both branches, not just error lines."""
import json, urllib.request, gzip, http.client

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

token = get_token()
repo = "NanoBotAgent/Aurelium"
api_headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-check"}

def get_logs(job_id):
    conn = http.client.HTTPSConnection("api.github.com")
    conn.request("GET", f"/repos/{repo}/actions/jobs/{job_id}/logs", headers=api_headers)
    resp = conn.getresponse()
    if resp.status in (301, 302, 303, 307):
        redirect_url = resp.getheader("Location")
        conn.close()
        parsed = urllib.parse.urlparse(redirect_url)
        conn2 = http.client.HTTPSConnection(parsed.netloc)
        conn2.request("GET", parsed.path + ("?" + parsed.query if parsed.query else ""))
        resp2 = conn2.getresponse()
        raw = resp2.read()
        conn2.close()
        try:
            return gzip.decompress(raw).decode("utf-8", errors="replace")
        except:
            return raw.decode("utf-8", errors="replace")
    conn.close()
    return ""

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    url = f"https://api.github.com/repos/{repo}/actions/runs?branch={branch}&per_page=1"
    req = urllib.request.Request(url, headers=api_headers)
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    run_id = data["workflow_runs"][0]["id"]
    
    url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
    req2 = urllib.request.Request(url2, headers=api_headers)
    with urllib.request.urlopen(req2) as resp2:
        jobs_data = json.loads(resp2.read())
    
    print(f"\n{'='*60}")
    print(f"BRANCH: {branch}")
    print(f"Run: {run_id} (sha: {data['workflow_runs'][0]['head_sha'][:8]})")
    print(f"{'='*60}")
    
    for job in jobs_data["jobs"]:
        conclusion = job.get("conclusion", "running")
        print(f"\n--- Job: {job['name']} [{conclusion}] ---")
        
        logs = get_logs(job["id"])
        lines = logs.split("\n")
        
        # Strip timestamps and show key content
        for line in lines:
            clean = line.strip()
            if len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            # Skip noisy lines
            if not clean:
                continue
            if any(skip in clean for skip in ["##[", "Downloading", "Download ", "Cache ", 
                    "Saved ", "Cleaning ", "Gradle ", "Task :", "BUILD SUCCESSFUL",
                    "node_modules", "npm ", "Post job", "Complete job"]):
                continue
            # Show everything important
            if any(kw in clean.lower() for kw in ["error", "warn", "fail", "pass", "exception",
                    "custom_item", "customitem", "scanner", "migration", "schema", "config",
                    "aurel", "plugin", "enabled", "test", "rcon", "mysql", "sql", "table",
                    "cannot find", "incompatible", "does not exist"]):
                print(f"  {clean[:200]}")

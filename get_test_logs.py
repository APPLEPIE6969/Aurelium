#!/usr/bin/env python3
"""Get detailed mysql-test and ingame-test logs for both branches."""
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
    
    for job in jobs_data["jobs"]:
        job_name = job["name"].lower()
        if "mysql" not in job_name and "ingame" not in job_name:
            continue
        
        conclusion = job.get("conclusion", "running")
        print(f"\n{'='*60}")
        print(f"BRANCH: {branch}")
        print(f"Job: {job['name']} [{conclusion}]")
        print(f"{'='*60}")
        
        logs = get_logs(job["id"])
        for line in logs.split("\n"):
            clean = line.strip()
            if len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                clean = clean[28:].strip()
            if not clean:
                continue
            # Skip purely noise lines
            if any(skip in clean for skip in ["##[", "Downloading", "Download ", "Cache ",
                    "Saved ", "node_modules", "npm ", "Post job", "Complete job",
                    "Adding repository", "Temporarily overriding", "safe.directory",
                    "Deleting the contents", "Initialized empty", "remote add origin",
                    "gc.auto", "core.sshCommand", "submodule foreach", "http.extraheader",
                    "protocol.version", "checkout --progress", "Switched to a new",
                    "set up to track", "tar -xf", "DEPENDENCY_GRAPH",
                    "deprecation", "DeprecationWarning"]):
                continue
            # Show ALL non-noise lines for these jobs
            print(f"  {clean[:250]}")

#!/usr/bin/env python3
"""Get mysql-test - filter for Aurelium plugin output only."""
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

url = f"https://api.github.com/repos/{repo}/actions/runs?branch=feature/custom-item-scanner&per_page=1"
req = urllib.request.Request(url, headers=api_headers)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read())

run_id = data["workflow_runs"][0]["id"]

url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
req2 = urllib.request.Request(url2, headers=api_headers)
with urllib.request.urlopen(req2) as resp2:
    jobs_data = json.loads(resp2.read())

for job in jobs_data["jobs"]:
    if "mysql" not in job["name"].lower():
        continue
    
    job_id = job["id"]
    print(f"Job: {job['name']} conclusion={job.get('conclusion')}")
    
    conn = http.client.HTTPSConnection("api.github.com")
    conn.request("GET", f"/repos/{repo}/actions/jobs/{job_id}/logs", headers=api_headers)
    resp3 = conn.getresponse()
    if resp3.status in (301, 302, 303, 307):
        redirect_url = resp3.getheader("Location")
        conn.close()
        parsed = urllib.parse.urlparse(redirect_url)
        conn2 = http.client.HTTPSConnection(parsed.netloc)
        conn2.request("GET", parsed.path + ("?" + parsed.query if parsed.query else ""))
        resp4 = conn2.getresponse()
        raw = resp4.read()
        conn2.close()
        try:
            logs = gzip.decompress(raw).decode("utf-8", errors="replace")
        except:
            logs = raw.decode("utf-8", errors="replace")
        
        # Filter for important lines only
        for line in logs.split("\n"):
            clean = line.strip()
            if len(clean) > 28 and clean[4:5] == "-" and clean[10:11] == "T":
                clean = clean[28:].strip()
            if not clean:
                continue
            
            # Only show Aurelium/plugin/server output, not infra noise
            lower = clean.lower()
            if any(kw in lower for kw in ["aurel", "custom_items", "custom item", "scan complete",
                                           "migration", "database", "pass", "fail", "error:", 
                                           "exception", "config", "enabled", "disabled",
                                           "loading server plugin", "enabling", "vault",
                                           "itemsadder", "oraxen", "mmoitems", "scanner",
                                           "schema", "table"]):
                if "PLEASE RESTART" not in clean and "zip file" not in lower:
                    print(f"  {clean[:350]}")

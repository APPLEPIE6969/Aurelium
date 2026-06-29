#!/usr/bin/env python3
"""Check CI status and get error logs for both branches."""
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
        try: return gzip.decompress(raw).decode("utf-8", errors="replace")
        except: return raw.decode("utf-8", errors="replace")
    conn.close()
    return ""

for branch in ["feature/custom-item-scanner", "feature/custom-item-scanner-1.21"]:
    url = f"https://api.github.com/repos/{repo}/actions/runs?branch={branch}&per_page=2"
    req = urllib.request.Request(url, headers=api_headers)
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
    
    print(f"\n{'='*60}")
    print(f"BRANCH: {branch}")
    print(f"{'='*60}")
    
    for run in data["workflow_runs"][:2]:
        rid = run["id"]
        sha = run["head_sha"][:8]
        msg = run["name"] or run["head_commit"]["message"][:60]
        conclusion = run.get("conclusion", run.get("status"))
        print(f"\n  Run {rid}: {conclusion} (sha: {sha}, msg: {msg[:60]})")
        
        url2 = f"https://api.github.com/repos/{repo}/actions/runs/{rid}/jobs"
        req2 = urllib.request.Request(url2, headers=api_headers)
        with urllib.request.urlopen(req2) as resp2:
            jobs = json.loads(resp2.read())
        
        for job in jobs["jobs"]:
            jc = job.get("conclusion", "running")
            jn = job["name"]
            print(f"    {jn}: {jc}")
            
            if jc == "failure":
                logs = get_logs(job["id"])
                for line in logs.split("\n"):
                    c = line.strip()
                    if len(c) > 28 and c[4:5] == '-' and c[10:11] == 'T':
                        c = c[28:].strip()
                    if not c: continue
                    if any(kw in c.lower() for kw in ["error:", "cannot find", "cannot access", "incompatible", "does not exist", "return type", "compact source"]):
                        print(f"      {c[:250]}")

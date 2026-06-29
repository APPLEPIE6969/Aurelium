#!/usr/bin/env python3
"""Get all failed steps for 26.x branch."""
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

# Get latest run
url = f"https://api.github.com/repos/{repo}/actions/runs?branch=feature/custom-item-scanner&per_page=1"
req = urllib.request.Request(url, headers=api_headers)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read())
run_id = data["workflow_runs"][0]["id"]
print(f"Run ID: {run_id}")

# Get jobs
url2 = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/jobs"
req2 = urllib.request.Request(url2, headers=api_headers)
with urllib.request.urlopen(req2) as resp2:
    jobs_data = json.loads(resp2.read())

for job in jobs_data["jobs"]:
    conclusion = job.get("conclusion", "running")
    print(f"\nJob: {job['name']} - {conclusion}")
    if conclusion == "failure":
        for step in job["steps"]:
            if step.get("conclusion") == "failure":
                print(f"  FAILED: Step #{step['number']} {step['name']}")
        
        # Get full log for this job
        job_id = job["id"]
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
            
            # Show ALL lines from the failed step
            lines = logs.split("\n")
            # Find the step that failed
            for i, line in enumerate(lines):
                clean = line.strip()
                if len(clean) > 28 and clean[4:5] == '-' and clean[10:11] == 'T':
                    clean = clean[28:].strip()
                if "BUILD FAILED" in clean or "error" in clean.lower() or "FAILURE" in clean:
                    # Show context around this line
                    start = max(0, i-2)
                    end = min(len(lines), i+3)
                    for j in range(start, end):
                        c = lines[j].strip()
                        if len(c) > 28 and c[4:5] == '-' and c[10:11] == 'T':
                            c = c[28:].strip()
                        print(f"  {c[:300]}")
        else:
            body = resp3.read().decode()
            print(f"  Log fetch status: {resp3.status}")
            conn.close()

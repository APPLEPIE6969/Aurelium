#!/usr/bin/env python3
import json, urllib.request, http.client, gzip

token = None
with open('/home/applepie69/.git-credentials') as f:
    for line in f:
        if 'github.com' in line.strip():
            parts = line.strip().split('://')[1]
            token = parts.split("@")[0].split(":")[1]

REPO = "NanoBotAgent/Aurelium"
headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-fix"}

url = f"https://api.github.com/repos/{REPO}/actions/runs/26313663296/jobs"
req = urllib.request.Request(url, headers=headers)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read())

for job in data["jobs"]:
    if job["name"] == "custom-item-detection-test":
        conn = http.client.HTTPSConnection("api.github.com")
        conn.request("GET", f"/repos/{REPO}/actions/jobs/{job['id']}/logs", headers=headers)
        resp = conn.getresponse()
        redirect_url = resp.getheader("Location")
        conn.close()
        
        parsed = urllib.parse.urlparse(redirect_url)
        conn2 = http.client.HTTPSConnection(parsed.netloc)
        conn2.request("GET", parsed.path + ("?" + parsed.query if parsed.query else ""))
        resp2 = conn2.getresponse()
        raw = resp2.read()
        conn2.close()
        try: logs = gzip.decompress(raw).decode("utf-8", errors="replace")
        except: logs = raw.decode("utf-8", errors="replace")
        
        # Print scanner/Aurelium/EI/SCore related lines
        for line in logs.split("\n"):
            c = line.strip()
            if len(c) > 28 and c[4:5] == '-' and c[10:11] == 'T':
                c = c[28:].strip()
            if not c: continue
            if any(kw in c.lower() for kw in ["executableitems", "score", "aurelium", "scanner", "scan", "custom", "loaded", "ok:", "warn:", "fail:", "done", "started", "paper"]):
                print(c[:300])

#!/usr/bin/env python3
"""Fix CI workflow - line-based replacement for SQL error check."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

def get_file(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch, "sha": sha}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()
content, sha = get_file(".github/workflows/build.yml", BRANCH, token)

lines = content.split("\n")
new_lines = []
replaced = False

for i, line in enumerate(lines):
    stripped = line.strip()
    
    # Replace the narrow SQL check with a broader one
    if 'grep -qi "SQLSyntaxErrorException' in line and not replaced:
        # Replace this line and the next few lines
        indent = line[:len(line) - len(line.lstrip())]
        new_lines.append(indent + 'if grep -qiE "SQLException|ambiguous|doesn.t.exist" logs/latest.log 2>/dev/null; then')
        replaced = True
        continue
    
    if replaced:
        # Skip old lines and replace with new content
        if '"FAIL: SQL syntax errors detected with MySQL"' in stripped:
            new_lines.append(indent + 'echo "FAIL: SQL errors detected with MySQL"')
            continue
        if 'grep -i "SQLSyntax' in stripped:
            new_lines.append(indent + 'grep -iE "SQLException|ambiguous|doesn.t.exist" logs/latest.log | grep -vi "PLEASE RESTART" | tail -15')
            continue
        if '"PASS: No SQL syntax errors with MySQL"' in stripped:
            new_lines.append(indent + 'echo "PASS: No SQL errors with MySQL"')
            replaced = False
            continue
    
    new_lines.append(line)

content = "\n".join(new_lines)

# Verify
if "ambiguous" in content:
    print("SQL error check updated - now catches all SQLException types + ambiguous columns")
else:
    print("WARNING: fix may not have applied correctly")

result = push_file(".github/workflows/build.yml", content,
                    "fix: broaden SQL error detection in mysql-test CI", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

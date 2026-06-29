#!/usr/bin/env python3
"""Fix CI workflow to catch all SQL errors, not just SQLSyntaxErrorException."""
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

# Fix the SQL error check to catch ALL SQL exceptions, not just SQLSyntaxErrorException
# Old check only catches SQLSyntaxErrorException|SQL syntax
# New check catches all SQLException subtypes and ambiguous column errors
old_check = '''if grep -qi "SQLSyntaxErrorException\\|SQL syntax" logs/latest.log 2>/dev/null; then
 echo "FAIL: SQL syntax errors detected with MySQL"
 grep -i "SQLSyntax\\|syntax error" logs/latest.log | tail -10
 kill $SERVER_PID 2>/dev/null || true
 exit 1
 fi
 echo "PASS: No SQL syntax errors with MySQL"'''

new_check = '''if grep -qi "SQLException\\|SQL syntax\\|ambiguous\\|doesn\'t exist" logs/latest.log 2>/dev/null; then
 echo "FAIL: SQL errors detected with MySQL"
 grep -iE "SQLException|syntax error|ambiguous|doesn.t.exist" logs/latest.log | grep -vi "PLEASE RESTART" | tail -15
 kill $SERVER_PID 2>/dev/null || true
 exit 1
 fi
 echo "PASS: No SQL errors with MySQL"'''

content = content.replace(old_check, new_check)

if "ambiguous" in content.split("RCON")[0]:
    print("SQL error check updated successfully")
else:
    print("WARNING: replacement may have failed")

result = push_file(".github/workflows/build.yml", content,
                    "fix: broaden SQL error detection in mysql-test to catch all SQLException types", BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

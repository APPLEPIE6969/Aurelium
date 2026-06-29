#!/usr/bin/env python3
"""Update CI workflow triggers on both feature branches."""
import os, json, base64, urllib.request, urllib.error

REPO = "NanoBotAgent/Aurelium"

def get_token():
    with open(os.path.expanduser("~/.git-credentials")) as f:
        for line in f:
            if "github.com" in line.strip():
                return line.strip().split("://")[1].split("@")[0].split(":")[1]
    raise RuntimeError("No token")

def get_file_content(path, branch, token):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}?ref={branch}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return base64.b64decode(data["content"]).decode("utf-8"), data["sha"]

def push_file(path, content, message, branch, token, sha):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch, "sha": sha}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()

# Update 26.1.x branch workflow
branch_26 = "feature/custom-item-scanner"
print(f"Updating workflow on {branch_26}...")
content, sha = get_file_content(".github/workflows/build.yml", branch_26, token)

content = content.replace(
    'on:\n  push:\n    branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname"]\n  pull_request:\n    branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname"]',
    'on:\n  push:\n    branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]\n  pull_request:\n    branches: ["1.4.3", main, "fix/mysql-compat-and-auction-displayname", "feature/custom-item-scanner"]'
)

result = push_file(".github/workflows/build.yml", content, "ci: add feature/custom-item-scanner to workflow triggers", branch_26, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

# Update 1.21.x branch workflow
branch_121 = "feature/custom-item-scanner-1.21"
print(f"Updating workflow on {branch_121}...")
content, sha = get_file_content(".github/workflows/build.yml", branch_121, token)

# The 1.21 branch may have different trigger branches, update generically
if "feature/custom-item-scanner-1.21" not in content:
    # Find the branches list and add our branch
    content = content.replace(
        'on:\n  push:\n    branches:',
        'on:\n  push:\n    branches:'
    )
    # More targeted: add after existing branch list
    lines = content.split('\n')
    new_lines = []
    in_branches_push = False
    in_branches_pr = False
    for i, line in enumerate(lines):
        new_lines.append(line)
        if 'push:' in line and 'branches:' not in line:
            # next line should have branches
            pass
        if '"feature/custom-item-scanner-1.21"' in line:
            break  # already added
    
    # Simple approach: just add our branch to both push and pull_request sections
    # Replace the first occurrence of the branches list under push
    import re
    content = re.sub(
        r'(on:\n  push:\n    branches: \[.*?\])',
        lambda m: m.group(1).rstrip(']') + ', "feature/custom-item-scanner-1.21"]',
        content,
        count=1
    )
    content = re.sub(
        r'(pull_request:\n    branches: \[.*?\])',
        lambda m: m.group(1).rstrip(']') + ', "feature/custom-item-scanner-1.21"]',
        content,
        count=1
    )

result = push_file(".github/workflows/build.yml", content, "ci: add feature/custom-item-scanner-1.21 to workflow triggers", branch_121, token, sha)
print(f"  Pushed ({result['content']['sha'][:8]})")

print("Done!")

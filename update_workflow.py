#!/usr/bin/env python3
"""
Update CI workflow to add custom-item-detection-test job.
"""
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

def push_file(path, content, message, branch, token, sha=None):
    url = f"https://api.github.com/repos/{REPO}/contents/{path}"
    encoded = base64.b64encode(content.encode("utf-8")).decode("ascii")
    body = {"message": message, "content": encoded, "branch": branch}
    if sha:
        body["sha"] = sha
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="PUT", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", 
        "Content-Type": "application/json", "User-Agent": "aurelium-fix"})
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

token = get_token()
workflow_code, workflow_sha = get_file(".github/workflows/build.yml", BRANCH, token)

# Check if detection test already exists
if "custom-item-detection-test:" in workflow_code:
    print("Detection test job already exists - updating")
    # Find and remove old one
    start = workflow_code.find("  custom-item-detection-test:")
    if start >= 0:
        # Find end of this job (next job at 2-space indent or EOF)
        end = len(workflow_code)
        lines_after = workflow_code[start:].split('\n')
        found_next = False
        pos = start
        for i, line in enumerate(lines_after[1:], 1):
            # A new job starts with a non-indented-at-4+ line that's not blank/comment
            if line and not line.startswith('    ') and not line.startswith('  ') and not line.startswith('#') and ':' in line:
                end = start + sum(len(l) + 1 for l in lines_after[:i])
                found_next = True
                break
        if not found_next:
            end = len(workflow_code)
        workflow_code = workflow_code[:start] + workflow_code[end:]
        print("Removed old detection test job")

# Build the new job
detection_job = """  custom-item-detection-test:
    needs: build
    runs-on: ubuntu-latest
    timeout-minutes: 15
    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_ROOT_PASSWORD: test
          MYSQL_DATABASE: aurelium_test
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping -h 127.0.0.1"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=5
    steps:
      - uses: actions/checkout@v4

      - name: Download Aurelium JAR
        uses: actions/download-artifact@v4
        with:
          name: Aurelium-plugin
          path: artifact/

      - name: Setup Java 25
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'

      - name: Download Paper
        run: |
          curl -sL "https://fill-data.papermc.io/v1/objects/26.1.2/builds/63/jars/Paper-26.1.2-63.jar" -o paper.jar
          echo "Paper downloaded"

      - name: Prepare Server
        run: |
          mkdir -p server/plugins
          cp paper.jar server/paper.jar
          cp artifact/Aurelium-*.jar server/plugins/
          cp ci/plugins/SCore-5.26.5.17.jar server/plugins/
          cp ci/plugins/ExecutableItems-7.26.5.17.jar server/plugins/

      - name: First Start
        working-directory: server
        run: |
          echo "eula=true" > eula.txt
          java -jar paper.jar --nogui 2>&1 | head -100 &
          SERVER_PID=$!
          timeout 60 bash -c 'while ! grep -q "Done" logs/latest.log 2>/dev/null; do sleep 2; done' || true
          kill $SERVER_PID 2>/dev/null || true
          sleep 2

      - name: Configure Server
        working-directory: server
        run: |
          sed -i 's/online-mode=true/online-mode=false/' server.properties
          sed -i 's/enforce-secure-profile=true/enforce-secure-profile=false/' server.properties
          mkdir -p plugins/Aurelium
          cat > plugins/Aurelium/config.yml << 'CFGEOF'
          database:
            type: mysql
            host: 127.0.0.1:3306
            database: aurelium_test
            username: root
            password: test
          custom-items:
            enabled: true
            discovery-methods:
              plugin-api: true
            auto-sync-to-config: true
            default-price-multiplier: 1.5
          CFGEOF

      - name: Start and Test Scanner
        working-directory: server
        run: |
          java -Xmx1024M -jar paper.jar --nogui > server.log 2>&1 &
          SERVER_PID=$!
          echo "Waiting for server..."
          timeout 120 bash -c 'while ! grep -q "Done" logs/latest.log 2>/dev/null; do sleep 2; done'
          echo "Server started"
          sleep 5
          if grep -qi "ExecutableItems" logs/latest.log; then
            echo "OK: ExecutableItems loaded"
          else
            echo "WARN: ExecutableItems not loaded"
          fi
          if grep -qi "Aurelium" logs/latest.log; then
            echo "OK: Aurelium loaded"
          else
            echo "FAIL: Aurelium not loaded"
          fi
          if grep -qi "Scanner" logs/latest.log; then
            echo "OK: Scanner ran"
            grep -i "Scanner\\|scan complete\\|items found" logs/latest.log || true
          else
            echo "WARN: No scanner output"
          fi
          cp logs/latest.log logs/pre-shutdown.log 2>/dev/null || true
          kill $SERVER_PID 2>/dev/null || true
          wait $SERVER_PID 2>/dev/null || true
"""

# Append the job to the workflow (before final newline)
workflow_code = workflow_code.rstrip() + "\n\n" + detection_job

result = push_file(".github/workflows/build.yml", workflow_code,
                    "ci: add custom-item-detection-test with real ExecutableItems/SCore JARs",
                    BRANCH, token, workflow_sha)
print(f"Pushed workflow (sha: {result['content']['sha'][:8]})")

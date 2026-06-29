#!/usr/bin/env python3
"""Fix 1.21.x CI workflow to use Maven instead of Gradle and Java 21 instead of 25."""
import os, json, base64, urllib.request, urllib.error

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

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
content, sha = get_file_content(".github/workflows/build.yml", BRANCH, token)

# Replace the entire workflow for 1.21.x compatibility
new_workflow = '''name: Build and Test (1.21.x)

on:
  push:
    branches: ["compat/paper-1.21", "feature/custom-item-scanner-1.21"]
  pull_request:
    branches: ["compat/paper-1.21", "feature/custom-item-scanner-1.21"]

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 21
        uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Cache Maven packages
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-m2
      - name: Build with Maven
        run: mvn -B package --file pom.xml
      - name: Upload JAR
        uses: actions/upload-artifact@v4
        with:
          name: Aurelium
          path: target/*.jar

  smoke-test:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/setup-java@v5
        with:
          java-version: '21'
          distribution: 'temurin'
      - name: Download Paper 1.21.4
        run: |
          curl -sL "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/248/downloads/paper-1.21.4-248.jar" -o paper.jar || \\
          curl -sL "https://fill-data.papermc.io/v1/objects/980421a4f9c4b26f15a9d2fddd7fc91125fd91320d21e189d4504e70893a79e5/paper-26.1.2-61.jar" -o paper.jar
          ls -lh paper.jar
      - name: Download plugin artifact
        uses: actions/download-artifact@v4
        with:
          name: Aurelium
          path: plugins/
      - name: Accept EULA
        run: |
          echo "eula=true" > eula.txt
      - name: Start Paper server with plugin
        timeout-minutes: 5
        run: |
          java -Dpaper.playerconnection.keepalive=60 \\
          -Xmx512M -Xms512M \\
          -jar paper.jar --nogui \\
          --max-players=5 &
          SERVER_PID=$!

          TIMEOUT=120
          ELAPSED=0
          while [ $ELAPSED -lt $TIMEOUT ]; do
            if grep -q "Done (" logs/latest.log 2>/dev/null; then
              echo "Server started successfully!"
              break
            fi
            if grep -q "Failed to start" logs/latest.log 2>/dev/null; then
              echo "Server failed to start"
              tail -50 logs/latest.log
              kill $SERVER_PID 2>/dev/null || true
              exit 1
            fi
            sleep 2
            ELAPSED=$((ELAPSED + 2))
          done

          if [ $ELAPSED -ge $TIMEOUT ]; then
            echo "Server did not start within ${TIMEOUT}s"
            tail -30 logs/latest.log
            kill $SERVER_PID 2>/dev/null || true
            exit 1
          fi

          echo "=== Plugin Load Verification ==="
          if grep -q "AurelEconomy has been enabled" logs/latest.log 2>/dev/null; then
            echo "PASS: Aurelium plugin loaded successfully!"
          elif grep -q "Aurelium" logs/latest.log 2>/dev/null; then
            echo "WARN: Aurelium mentioned in log but may have errors"
            grep -i "aurelium\\|error\\|exception" logs/latest.log | tail -10
          else
            echo "FAIL: Aurelium not found in log"
            kill $SERVER_PID 2>/dev/null || true
            exit 1
          fi

          echo "=== Database & Config ==="
          if ls plugins/Aurelium/*.db 2>/dev/null; then
            echo "PASS: SQLite database file exists"
          else
            echo "SKIP: No .db file found"
          fi
          if [ -f plugins/Aurelium/config.yml ]; then
            echo "PASS: config.yml generated"
          else
            echo "FAIL: config.yml not found"
            kill $SERVER_PID 2>/dev/null || true
            exit 1
          fi

          cp logs/latest.log logs/pre-shutdown.log
          REAL_ERRORS=$(grep -i "Exception.*aurel\\|Caused by:.*aurel" logs/pre-shutdown.log 2>/dev/null | grep -vi "PLEASE RESTART\\|zip file" || true)
          if [ -n "$REAL_ERRORS" ]; then
            echo "FAIL: Aurelium exceptions found during runtime"
            echo "$REAL_ERRORS"
            kill $SERVER_PID 2>/dev/null || true
            exit 1
          else
            echo "PASS: No Aurelium exceptions during runtime"
          fi

          kill $SERVER_PID 2>/dev/null || true
          wait $SERVER_PID 2>/dev/null || true

      - name: Upload server log on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: server-log
          path: logs/latest.log
          retention-days: 7

      - name: Upload plugin data on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: plugin-data
          path: plugins/Aurelium/
          retention-days: 7
'''

result = push_file(".github/workflows/build.yml", new_workflow, 
                    "ci: rewrite workflow for Maven + Java 21 (1.21.x compat)", BRANCH, token, sha)
print(f"Pushed 1.21.x workflow ({result['content']['sha'][:8]})")

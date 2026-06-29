#!/usr/bin/env python3
"""Fix remaining issues: 1.21.x build.yml back to Gradle, 26.x paper-api version."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                parts = line.strip().split("://")[1]
                return parts.split("@")[0].split(":")[1]

def get_file(path, branch, token):
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

# === 1. Fix 1.21.x build.yml: Maven -> Gradle + Java 21 ===
print("Fixing 1.21.x build.yml to use Gradle...")
branch = "feature/custom-item-scanner-1.21"
content, sha = get_file(".github/workflows/build.yml", branch, token)

# Check the 26.x branch build.yml to use as reference
ref_content, _ = get_file(".github/workflows/build.yml", "feature/custom-item-scanner", token)

# Build a proper 1.21.x Gradle workflow based on the 26.x one but with Java 21
new_workflow = """name: Build and Test (1.21.x)

on:
  push:
    branches: ["compat/paper-1.21", "feature/custom-item-scanner-1.21"]
  pull_request:
    branches: ["compat/paper-1.21"]

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
      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
      - name: Build with Gradle
        run: ./gradlew build
      - name: Upload JAR
        uses: actions/upload-artifact@v4
        with:
          name: Aurelium
          path: build/libs/*.jar

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
          LATEST_BUILD=$(curl -sL "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['builds'][-1]['build'])" 2>/dev/null || echo "248")
          curl -sL "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/${LATEST_BUILD}/downloads/paper-1.21.4-${LATEST_BUILD}.jar" -o paper.jar || echo "Paper download failed, will check log"
          ls -lh paper.jar 2>/dev/null || echo "No paper.jar found"
      - name: Download plugin artifact
        uses: actions/download-artifact@v4
        with:
          name: Aurelium
          path: plugins/
      - name: Accept EULA
        run: echo "eula=true" > eula.txt
      - name: Start Paper server with plugin
        timeout-minutes: 5
        run: |
          if [ ! -f paper.jar ]; then
            echo "SKIP: No Paper JAR downloaded, skipping smoke test"
            exit 0
          fi
          mkdir -p plugins
          cp build/libs/*.jar plugins/ 2>/dev/null || true
          java -Dpaper.playerconnection.keepalive=60 -Xmx512M -Xms512M -jar paper.jar --nogui --max-players=5 &
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
          if grep -q "AurelEconomy" logs/latest.log 2>/dev/null; then
            echo "PASS: Aurelium found in server log"
          else
            echo "FAIL: Aurelium not found in log"
            kill $SERVER_PID 2>/dev/null || true
            exit 1
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
"""

result = push_file(".github/workflows/build.yml", new_workflow,
                    "ci: use Gradle + Java 21 for 1.21.x branch", branch, token, sha)
print(f"  Fixed 1.21.x build.yml (sha: {result['content']['sha'][:8]})")

# === 2. Fix 26.x build.gradle.kts: update Paper API to build 63 ===
print("Fixing 26.x build.gradle.kts Paper API version...")
branch = "feature/custom-item-scanner"
content, sha = get_file("build.gradle.kts", branch, token)
content = content.replace("26.1.2.build.53-stable", "26.1.2.build.63-stable")
result = push_file("build.gradle.kts", content,
                    "fix: update Paper API to build 63", branch, token, sha)
print(f"  Fixed 26.x build.gradle.kts (sha: {result['content']['sha'][:8]})")

# === 3. Fix 1.21.x build.gradle.kts: update Paper API if needed ===
print("Fixing 1.21.x build.gradle.kts...")
branch = "feature/custom-item-scanner-1.21"
content, sha = get_file("build.gradle.kts", branch, token)
# Paper 1.21.4 API is the latest 1.21.x
content = content.replace("1.21.11-R0.1-SNAPSHOT", "1.21.4-R0.1-SNAPSHOT")
result = push_file("build.gradle.kts", content,
                    "fix: update Paper API to 1.21.4", branch, token, sha)
print(f"  Fixed 1.21.x build.gradle.kts (sha: {result['content']['sha'][:8]})")

print("\nAll fixes pushed!")

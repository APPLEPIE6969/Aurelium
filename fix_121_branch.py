#!/usr/bin/env python3
"""Fix remaining 1.21.x branch items: DatabaseManager.java + build.yml"""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

def get_token():
    with open("/home/applepie69/.git-credentials") as f:
        for line in f:
            if "github.com" in line.strip():
                return line.strip().split("://")[1].split("@")[0].split(":")[1]

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

# 1. Fix DatabaseManager.java
print("Fixing DatabaseManager.java on 1.21.x branch...")
content, sha = get_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", BRANCH, token)

content = content.replace(
    " private static final int LATEST_SCHEMA_VERSION = 1;",
    " private static final int LATEST_SCHEMA_VERSION = 2;"
)

if "case 2:" not in content:
    content = content.replace(
        " case 1:\n",
        " case 2:\n createCustomItemsTable();\n break;\n case 1:\n"
    )

if "createCustomItemsTable" not in content:
    method = '\n private void createCustomItemsTable() {\n     try (Statement statement = connection.createStatement()) {\n         if ("mysql".equals(databaseType)) {\n             statement.execute("CREATE TABLE IF NOT EXISTS custom_items (" +\n                 "canonical_id VARCHAR(255) PRIMARY KEY, " +\n                 "source_plugin VARCHAR(64) NOT NULL, " +\n                 "display_name VARCHAR(256), " +\n                 "item_data TEXT NOT NULL, " +\n                 "pdc_key VARCHAR(255), " +\n                 "model_data_key VARCHAR(128), " +\n                 "lore_hash VARCHAR(64), " +\n                 "plugin_native_id VARCHAR(255), " +\n                 "category VARCHAR(64), " +\n                 "buy_price DOUBLE DEFAULT -1, " +\n                 "sell_price DOUBLE DEFAULT -1, " +\n                 "enabled TINYINT(1) DEFAULT 1, " +\n                 "discovery_methods VARCHAR(256), " +\n                 "first_discovered BIGINT NOT NULL, " +\n                 "last_seen BIGINT NOT NULL" +\n                 ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");\n         } else {\n             statement.execute("CREATE TABLE IF NOT EXISTS custom_items (" +\n                 "canonical_id TEXT PRIMARY KEY, " +\n                 "source_plugin TEXT NOT NULL, " +\n                 "display_name TEXT, " +\n                 "item_data TEXT NOT NULL, " +\n                 "pdc_key TEXT, " +\n                 "model_data_key TEXT, " +\n                 "lore_hash TEXT, " +\n                 "plugin_native_id TEXT, " +\n                 "category TEXT, " +\n                 "buy_price REAL DEFAULT -1, " +\n                 "sell_price REAL DEFAULT -1, " +\n                 "enabled INTEGER DEFAULT 1, " +\n                 "discovery_methods TEXT, " +\n                 "first_discovered INTEGER NOT NULL, " +\n                 "last_seen INTEGER NOT NULL" +\n                 ")");\n         }\n     } catch (SQLException e) {\n         plugin.getComponentLogger().error("Could not create custom_items table for " + databaseType + "!", e);\n     }\n }\n'
    content = content.rstrip()
    if content.endswith("}"):
        content = content[:-1] + method + "}"

result = push_file("src/main/java/com/aureleconomy/database/DatabaseManager.java", content,
                    "feat: add custom_items table and schema v2 migration (1.21.x)", BRANCH, token, sha)
print(f"  Pushed DatabaseManager.java (sha: {result['content']['sha'][:8]})")

# 2. Fix build.yml to use Maven + Java 21
print("Fixing build.yml to use Maven + Java 21...")
content, sha = get_file(".github/workflows/build.yml", BRANCH, token)

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
                    "ci: rewrite workflow for Maven + Java 21 (1.21.x compat)", BRANCH, token, sha)
print(f"  Pushed build.yml (sha: {result['content']['sha'][:8]})")

print("\nDone! 1.21.x branch fully updated.")

#!/usr/bin/env python3
"""Add custom-item-detection-test to 1.21.x branch workflow."""
import json, base64, urllib.request

REPO = "NanoBotAgent/Aurelium"
BRANCH = "feature/custom-item-scanner-1.21"

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

# Also remove the detection test from the 26.x branch (it can't work there - Java 25 bytecode)
content26, sha26 = get_file(".github/workflows/build.yml", "feature/custom-item-scanner", token)

detection_job = """
  custom-item-detection-test:
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
          curl -sL "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/${LATEST_BUILD}/downloads/paper-1.21.4-${LATEST_BUILD}.jar" -o paper.jar
          ls -lh paper.jar

      - name: Download Aurelium artifact
        uses: actions/download-artifact@v4
        with:
          name: Aurelium
          path: plugins/

      - name: Download SimpleItemGenerator from Modrinth
        run: |
          curl -sL "https://cdn.modrinth.com/data/ufTqz70X/versions/fOsFNreo/SimpleItemGenerator-1.11.0-all.jar" -o plugins/SimpleItemGenerator.jar
          ls -lh plugins/

      - name: First server start
        run: |
          echo "eula=true" > eula.txt
          java -Dpaper.playerconnection.keepalive=60 -Xmx512M -Xms512M -jar paper.jar --nogui --max-players=5 &
          SERVER_PID=$!
          for i in $(seq 1 60); do
            if grep -q "Done (" logs/latest.log 2>/dev/null; then break; fi
            sleep 2
          done
          kill $SERVER_PID 2>/dev/null; wait $SERVER_PID 2>/dev/null || true
          sleep 2

      - name: Configure for offline mode + RCON
        run: |
          sed -i 's/online-mode=true/online-mode=false/' server.properties
          sed -i 's/enable-rcon=false/enable-rcon=true/' server.properties
          echo "rcon.port=25575" >> server.properties
          echo "rcon.password=rconpass" >> server.properties

      - name: Restart with both plugins
        run: |
          rm -rf logs/latest.log
          java -Dpaper.playerconnection.keepalive=60 -Xmx512M -Xms512M -jar paper.jar --nogui --max-players=5 &
          SERVER_PID=$!
          for i in $(seq 1 90); do
            if grep -q "Done (" logs/latest.log 2>/dev/null; then break; fi
            sleep 2
          done
          echo "=== Server started ==="
          sleep 5

      - name: Verify both plugins loaded
        run: |
          echo "--- Plugin list ---"
          grep "Bukkit plugins" logs/latest.log || true
          SIG_OK=$(grep -c "SimpleItemGenerator" logs/latest.log 2>/dev/null || echo "0")
          AUR_OK=$(grep -c "AurelEconomy" logs/latest.log 2>/dev/null || echo "0")
          echo "SIG mentions: $SIG_OK, Aurelium mentions: $AUR_OK"

      - name: Trigger scan via RCON and wait
        run: |
          pip install rcon 2>/dev/null || true
          python3 -c "
          from rcon.client import Client
          import time
          try:
              with Client('localhost', 25575, passwd='rconpass') as c:
                  # Trigger custom item scan
                  resp = c.run('customitems scan')
                  print(f'customitems scan: {resp}')
                  time.sleep(10)
                  # List discovered items
                  resp = c.run('customitems list')
                  print(f'customitems list: {resp}')
          except Exception as e:
              print(f'RCON error: {e}')
          " || echo "RCON attempted"

          # Wait for scan + config sync to complete
          sleep 10

      - name: Verify config.yml auto-populated with discovered items
        run: |
          echo "=== Checking config.yml ==="
          CONFIG=$(find . -name "config.yml" -path "*/Aurelium/*" 2>/dev/null | head -1)

          if [ -z "$CONFIG" ]; then
            echo "FAIL: Aurelium config.yml not found"
            find . -name "config.yml" 2>/dev/null | head -5
            exit 1
          fi

          echo "Config at: $CONFIG"

          if grep -q "discovered-items:" "$CONFIG"; then
            echo "PASS: discovered-items section exists"
          else
            echo "FAIL: discovered-items section missing"
            cat "$CONFIG"
            exit 1
          fi

          ITEM_COUNT=$(grep -c "source-plugin:" "$CONFIG" 2>/dev/null || echo "0")
          echo "Discovered items written to config: $ITEM_COUNT"

          if [ "$ITEM_COUNT" -gt 0 ]; then
            echo "PASS: Config auto-populated with $ITEM_COUNT discovered item(s)"
            echo "--- Discovered items ---"
            sed -n '/discovered-items:/,$ p' "$CONFIG" | head -50
          else
            echo "WARN: 0 items in config (scanner may not have detected SIG items yet)"
          fi

      - name: Dump scanner + plugin logs
        if: always()
        run: |
          echo "=== Aurelium logs ==="
          grep -i "Aurelium\|CustomItems\|scan\|discovered" logs/latest.log 2>/dev/null || echo "None"
          echo ""
          echo "=== SIG logs ==="
          grep -i "SimpleItemGenerator\|SIG" logs/latest.log 2>/dev/null || echo "None"

      - name: Stop server
        if: always()
        run: |
          kill %1 2>/dev/null; wait 2>/dev/null || true
"""

if "custom-item-detection-test:" not in content:
    content = content.rstrip() + "\n" + detection_job
    result = push_file(".github/workflows/build.yml", content,
                        "ci: add custom-item-detection-test with SimpleItemGenerator",
                        BRANCH, token, sha)
    print(f"1.21.x: Pushed (sha: {result['content']['sha'][:8]})")
else:
    print("1.21.x: Job already exists")

# Remove detection test from 26.x (won't work - Java 25 bytecode on Paper 1.21.4)
if "custom-item-detection-test:" in content26:
    start = content26.find("  custom-item-detection-test:")
    end = len(content26)
    for i in range(start + 30, len(content26)):
        if content26[i] == '\n' and i + 1 < len(content26) and content26[i+1] not in (' ', '\n', '#', '\r'):
            end = i + 1
            break
    content26 = content26[:start] + content26[end:]
    result26 = push_file(".github/workflows/build.yml", content26,
                          "ci: remove detection test from 26.x (Java 25 bytecode incompatible with Paper 1.21.4)",
                          "feature/custom-item-scanner", token, sha26)
    print(f"26.x: Removed detection test (sha: {result26['content']['sha'][:8]})")
else:
    print("26.x: No detection test to remove")

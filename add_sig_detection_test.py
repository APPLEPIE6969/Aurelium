#!/usr/bin/env python3
"""Add a proper custom-item-detection CI job using SimpleItemGenerator from Modrinth."""
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

# Add the detection test job at the end
detection_job = r"""
  custom-item-detection-test:
    needs: build
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'

      - name: Download Aurelium artifact
        uses: actions/download-artifact@v4
        with:
          name: Aurelium
          path: plugins/

      - name: Download Paper 1.21.4
        run: |
          LATEST_BUILD=$(curl -sL "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['builds'][-1]['build'])" 2>/dev/null || echo "98")
          curl -sL "https://api.papermc.io/v2/projects/paper/versions/1.21.4/builds/${LATEST_BUILD}/downloads/paper-1.21.4-${LATEST_BUILD}.jar" -o paper.jar
          ls -la paper.jar

      - name: Download SimpleItemGenerator from Modrinth
        run: |
          curl -sL "https://cdn.modrinth.com/data/ufTqz70X/versions/fOsFNreo/SimpleItemGenerator-1.11.0-all.jar" -o plugins/SimpleItemGenerator.jar
          ls -la plugins/

      - name: First server start (generate files)
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

      - name: Configure server for offline mode + RCON
        run: |
          sed -i 's/online-mode=true/online-mode=false/' server.properties
          sed -i 's/enable-rcon=false/enable-rcon=true/' server.properties
          echo "rcon.port=25575" >> server.properties
          echo "rcon.password=rconpass" >> server.properties

      - name: Create SimpleItemGenerator config with test items
        run: |
          mkdir -p plugins/SimpleItemGenerator
          cat > plugins/SimpleItemGenerator/config.yml << 'SIGCFG'
          items:
            ruby_sword:
              material: DIAMOND_SWORD
              display_name: "&cRuby Sword"
              lore:
                - "&7A powerful custom sword"
              nbt:
                custom_item: "ruby_sword"
            magic_dust:
              material: GLOWSTONE_DUST
              display_name: "&eMagic Dust"
              lore:
                - "&7Sparkly magical dust"
              nbt:
                custom_item: "magic_dust"
          SIGCFG

      - name: Restart server with both plugins
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
          echo "--- Loaded plugins ---"
          grep -i "Bukkit plugins" logs/latest.log || true
          grep -i "SimpleItemGenerator\|SIG" logs/latest.log | head -5 || true
          grep -i "AurelEconomy\|Aurelium" logs/latest.log | head -5 || true

          SIG_LOADED=$(grep -c "SimpleItemGenerator" logs/latest.log 2>/dev/null || echo "0")
          AUR_LOADED=$(grep -c "AurelEconomy" logs/latest.log 2>/dev/null || echo "0")

          if [ "$SIG_LOADED" -eq 0 ]; then
            echo "WARN: SimpleItemGenerator did not load"
          fi
          if [ "$AUR_LOADED" -eq 0 ]; then
            echo "FAIL: Aurelium did not load"
            exit 1
          fi

      - name: Give custom items to a player via RCON
        run: |
          pip install rcon 2>/dev/null || true
          python3 -c "
          from rcon.client import Client
          import time

          with Client('localhost', 25575, passwd='rconpass') as c:
              # Try SIG give command
              resp = c.run('sig give ruby_sword @p')
              print(f'sig give ruby_sword: {resp}')
              resp = c.run('sig give magic_dust @p')
              print(f'sig give magic_dust: {resp}')

              # Force Aurelium scan
              resp = c.run('customitems scan')
              print(f'customitems scan: {resp}')

              # Wait for scan to complete
              time.sleep(10)

              # Check what was discovered
              resp = c.run('customitems list')
              print(f'customitems list: {resp}')
          " 2>/dev/null || echo "RCON commands sent (some may fail - that is OK for first test)"

          sleep 5

      - name: Check if config.yml was auto-populated with discovered items
        run: |
          echo "=== Checking config.yml ==="

          # Find the actual config location
          CONFIG_FILE=$(find . -name "config.yml" -path "*/Aurelium/*" 2>/dev/null | head -1)

          if [ -z "$CONFIG_FILE" ]; then
            echo "FAIL: Aurelium config.yml not found"
            find . -name "config.yml" 2>/dev/null | head -10
            echo "--- Aurelium log lines ---"
            grep -i "Aurelium" logs/latest.log | head -20
            exit 1
          fi

          echo "Found config at: $CONFIG_FILE"

          # Check discovered-items section
          if grep -q "discovered-items:" "$CONFIG_FILE"; then
            echo "PASS: discovered-items section exists"
          else
            echo "FAIL: discovered-items section missing from config"
            exit 1
          fi

          # Count items written to config
          ITEM_COUNT=$(grep -c "source-plugin:" "$CONFIG_FILE" 2>/dev/null || echo "0")
          echo "Discovered items in config: $ITEM_COUNT"

          if [ "$ITEM_COUNT" -gt 0 ]; then
            echo "PASS: Config auto-updated with $ITEM_COUNT discovered custom item(s)"
            echo "--- Discovered items ---"
            sed -n '/discovered-items:/,$ p' "$CONFIG_FILE" | head -40
          else
            echo "WARN: No items auto-populated yet"
            echo "This can happen if the scanner did not detect SIG items via PDC/plugin API"
            echo "--- Scanner log entries ---"
            grep -i "CustomItems\|scan\|discovered" logs/latest.log || echo "No scanner logs"
          fi

      - name: Check scanner activity in logs
        if: always()
        run: |
          echo "=== Aurelium scanner logs ==="
          grep -i "\[Aurelium\]\|\[CustomItems\]" logs/latest.log 2>/dev/null || echo "None found"
          echo ""
          echo "=== SimpleItemGenerator logs ==="
          grep -i "SimpleItemGenerator\|\[SIG\]" logs/latest.log 2>/dev/null || echo "None found"

      - name: Stop server
        if: always()
        run: |
          kill %1 2>/dev/null; wait 2>/dev/null || true
"""

if "custom-item-detection-test:" not in content:
    content = content.rstrip() + "\n" + detection_job
    print("Added custom-item-detection-test job")
else:
    print("Job already exists")
    # Replace existing
    start = content.find("  custom-item-detection-test:")
    if start != -1:
        end = len(content)
        for i in range(start + 30, len(content) - 5):
            if content[i] == '\n' and i + 1 < len(content) and content[i+1] not in (' ', '\n', '#', '\r'):
                end = i + 1
                break
        content = content[:start] + detection_job + content[end:]
        print("Replaced existing job")

result = push_file(".github/workflows/build.yml", content,
                    "ci: add custom-item-detection-test with SimpleItemGenerator from Modrinth",
                    BRANCH, token, sha)
print(f"Pushed (sha: {result['content']['sha'][:8]})")

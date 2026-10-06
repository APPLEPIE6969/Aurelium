#!/usr/bin/env python3
"""Guard the retired-dashboard-host rewrite.

A user upgrading from a released 1.5.x build still has the retired host in
web.cloud.url, because Bukkit never rewrites an existing config.yml. CloudSyncManager
rewrites it at startup. These checks pin that behaviour and stop the retired host
from being reintroduced where it would actually break people.

Run directly:  python ci/check_cloud_url_migration.py
"""
import os
import re
import sys

import yaml

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RETIRED = "webaureliummc.onrender.com"
CURRENT = "https://aurelium.alwaysdata.net"

errors = []


def check(name, ok, detail=""):
    print(f"{'PASS' if ok else 'FAIL'}: {name}" + (f" :: {detail}" if detail else ""))
    if not ok:
        errors.append(name)


def read(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as fh:
        return fh.read()


# 1. The shipped default must be the current dashboard, not a dead one.
cfg = yaml.safe_load(read("src/main/resources/config.yml"))
url = cfg["web"]["cloud"]["url"]
check("config.yml ships the current dashboard", CURRENT in url, url)

# 2. The rewrite must exist and be reachable from the constructor.
java = read("src/main/java/com/aureleconomy/web/CloudSyncManager.java")
check("CloudSyncManager declares CURRENT_DASHBOARD_URL", "CURRENT_DASHBOARD_URL" in java)
check(
    "the retired host is in the rewrite list, not used as a live default",
    re.search(r"RETIRED_DASHBOARD_HOSTS\s*=\s*\{[^}]*" + re.escape(RETIRED), java, re.S)
    is not None,
)
check(
    "baseUrl is resolved through the rewrite, not read straight from config",
    "resolveDashboardUrl()" in java and re.search(
        r"this\.baseUrl\s*=\s*resolveDashboardUrl\(\)", java
    ) is not None,
)
check(
    "the rewrite persists the change",
    re.search(r"resolveDashboardUrl.*?saveConfig\(\)", java, re.S) is not None,
)
check(
    "getString no longer supplies the URL directly",
    'getString("web.cloud.url", "http' not in java,
)

# 3. Host comparison must be tolerant of scheme, case and trailing slash.
check("host comparison lower-cases", "toLowerCase" in java)
check("host comparison strips the scheme", '"://"' in java)

# 4. The retired host must not appear as a live default anywhere in source.
for rel in [
    "src/main/resources/config.yml",
    "src/main/java/com/aureleconomy/web/CloudSyncManager.java",
]:
    text = read(rel)
    # Allowed only inside the rewrite list, on a line that is a list entry.
    for i, line in enumerate(text.splitlines(), 1):
        if RETIRED in line:
            is_list_entry = re.match(r'\s*"' + re.escape(RETIRED) + r'"\s*,?\s*$', line)
            check(
                f"{os.path.basename(rel)}:{i} only names the retired host in the rewrite list",
                is_list_entry is not None,
                line.strip()[:80],
            )

# 5. The old-config fixtures must still represent a real pre-upgrade install.
#    An earlier commit rewrote them to the new host, which meant the migration
#    jobs asserted nothing at all.
for rel in ["ci/old_config_v1.yml", "ci/old_config_v152.yml"]:
    path = os.path.join(REPO, rel)
    if not os.path.isfile(path):
        print(f"SKIP: {rel} not present")
        continue
    doc = yaml.safe_load(open(path, encoding="utf-8"))
    f_url = doc.get("web", {}).get("cloud", {}).get("url", "")
    check(f"{rel} keeps the retired host so upgrades are exercised", RETIRED in f_url, f_url)

print()
if errors:
    print(f"{len(errors)} check(s) FAILED:")
    for e in errors:
        print(f"  - {e}")
    sys.exit(1)
print("ALL CLOUD URL MIGRATION CHECKS PASS")
#!/usr/bin/env python3
"""Config migration test for Aurelium 1.5.2 -> 1.5.3.

Verifies that:
1. Config loads correctly (config-version stays 2)
2. User-modified values are preserved
3. New keys from defaults (buy-orders) are added
4. Cloud dashboard registration still works
"""

import yaml
import sys
import os
import urllib.request
import urllib.error
import json
import uuid
import time

CONFIG_PATH = os.environ.get("CONFIG_PATH", "plugins/Aurelium/config.yml")


def load_config(path):
    with open(path, "r") as f:
        return yaml.safe_load(f) or {}


def test_cloud_dashboard(config):
    """Test that the cloud dashboard accepts new registrations."""
    errors = []
    web_cloud = config.get("web", {}).get("cloud", {})
    base_url = web_cloud.get("url", "").rstrip("/")

    print(f"DEBUG: web.cloud config = {web_cloud}")
    print(f"DEBUG: base_url = '{base_url}'")

    if not base_url:
        errors.append("FAIL: web.cloud.url is empty, cannot test dashboard")
        return errors

    test_server_id = f"ci-test-152-{uuid.uuid4().hex[:12]}-{int(time.time())}"
    test_api_key = f"ci-key-{uuid.uuid4().hex[:16]}"

    print(f"INFO: Testing cloud dashboard at {base_url}")
    print(f"INFO: Using test server-id: {test_server_id}")

    # Test 1: Health check
    try:
        req = urllib.request.Request(base_url, method="GET")
        req.add_header("Accept", "application/json")
        with urllib.request.urlopen(req, timeout=15) as resp:
            print(f"PASS: Cloud dashboard is reachable (HTTP {resp.status})")
    except urllib.error.HTTPError as e:
        print(f"PASS: Cloud dashboard is reachable (HTTP {e.code})")
    except urllib.error.URLError as e:
        errors.append(f"FAIL: Cloud dashboard unreachable: {e.reason}")
        return errors
    except Exception as e:
        errors.append(f"FAIL: Cloud dashboard connection error: {e}")
        return errors

    errors

    # Test 2: Registration must succeed
    try:
        reg_url = base_url + "/api/register"
        payload = json.dumps({
            "serverId": test_server_id,
            "apiKey": test_api_key,
            "serverName": "CI-ConfigMigrationTest-152"
        }).encode("utf-8")
        req = urllib.request.Request(reg_url, data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("X-Api-Key", test_api_key)
        print(f"DEBUG: POST {reg_url}")
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8")
            print(f"DEBUG: /api/register response: HTTP {resp.status}, body={body[:200]}")
            if resp.status == 200:
                print(f"PASS: /api/register succeeded (HTTP 200)")
            else:
                errors.append(f"FAIL: /api/register returned HTTP {resp.status}, expected 200")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else ""
        print(f"DEBUG: /api/register HTTPError: {e.code}, body={body[:200]}")
        if e.code == 403:
            errors.append(f"FAIL: /api/register returned 403 - dashboard rejected new registration. Body: {body[:200]}")
        elif e.code == 404:
            errors.append(f"FAIL: /api/register returned 404 - endpoint missing")
        elif e.code == 503:
            errors.append(f"FAIL: /api/register returned 503 - dashboard not ready")
        else:
            errors.append(f"FAIL: /api/register returned HTTP {e.code}: {body[:200]}")
        return errors
    except Exception as e:
        print(f"DEBUG: /api/register Exception: {e}")
        errors.append(f"FAIL: /api/register request failed: {e}")
        return errors

    # Test 3: Sync must succeed after registration
    try:
        sync_url = base_url + "/api/sync"
        payload = json.dumps({"serverId": test_server_id}).encode("utf-8")
        req = urllib.request.Request(sync_url, data=payload, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("X-Api-Key", test_api_key)
        print(f"DEBUG: POST {sync_url}")
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = resp.read().decode("utf-8")
            print(f"DEBUG: /api/sync response: HTTP {resp.status}, body={body[:200]}")
            if resp.status == 200:
                print(f"PASS: /api/sync succeeded after registration (HTTP 200)")
            else:
                errors.append(f"FAIL: /api/sync returned HTTP {resp.status}, expected 200")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8") if e.fp else ""
        print(f"DEBUG: /api/sync HTTPError: {e.code}, body={body[:200]}")
        if e.code == 403:
            errors.append(f"FAIL: /api/sync returned 403 - server not recognized after registration")
        elif e.code == 503:
            errors.append(f"FAIL: /api/sync returned 503 - dashboard not ready")
        else:
            errors.append(f"FAIL: /api/sync returned HTTP {e.code}")
    except Exception as e:
        print(f"DEBUG: /api/sync Exception: {e}")
        errors.append(f"FAIL: /api/sync request failed: {e}")

    return errors


def test_migration():
    config = load_config(CONFIG_PATH)
    errors = []

    print(f"DEBUG: Loaded config keys: {list(config.keys())}")

    # 1. config-version should be 2 (no change from 1.5.2)
    cv = config.get("config-version", 0)
    if cv != 2:
        errors.append(f"FAIL: config-version = {cv}, expected 2")
    else:
        print(f"PASS: config-version = {cv}")

    # 2. economy.currencies should survive
    currencies = config.get("economy", {}).get("currencies", {})
    if not currencies:
        errors.append("FAIL: economy.currencies is missing/empty")
    elif "Aurels" not in currencies:
        errors.append("FAIL: economy.currencies.Aurels missing")
    else:
        aurels = currencies.get("Aurels", {})
        symbol = aurels.get("symbol", "")
        starting = aurels.get("starting-balance", 0)
        if symbol != "\u20b3":
            errors.append(f"FAIL: economy.currencies.Aurels.symbol = '{symbol}', expected '\u20b3'")
        else:
            print(f"PASS: economy.currencies.Aurels preserved (symbol={symbol}, starting-balance={starting})")

    # 3. web.cloud should survive
    web_cloud = config.get("web", {}).get("cloud", {})
    if not web_cloud:
        errors.append("FAIL: web.cloud is missing/empty")
    else:
        url = web_cloud.get("url", "")
        if "webaureliummc" in url or url == "https://webaureliummc.onrender.com":
            print(f"PASS: web.cloud.url preserved ({url})")
        else:
            errors.append(f"FAIL: web.cloud.url = '{url}', expected cloud dashboard URL")

    # 4. web.local should survive
    web_local = config.get("web", {}).get("local", {})
    if not web_local:
        errors.append("FAIL: web.local is missing/empty")
    else:
        print(f"PASS: web.local preserved (host={web_local.get('host','')})")

    # 5. User-modified values should be preserved
    default_currency = config.get("economy", {}).get("default-currency", "")
    if default_currency == "Aurels":
        print(f"PASS: economy.default-currency preserved ({default_currency})")
    else:
        errors.append(f"FAIL: economy.default-currency = '{default_currency}', expected 'Aurels'")

    # 6. market section should survive
    market = config.get("market", {})
    if not market:
        errors.append("FAIL: market section missing")
    else:
        gui_mode = market.get("gui-mode", "")
        if gui_mode == "modern":
            print(f"PASS: market.gui-mode preserved ({gui_mode})")
        else:
            errors.append(f"FAIL: market.gui-mode = '{gui_mode}', expected 'modern'")

    # 7. auction-house section should survive
    auction = config.get("auction-house", {})
    if not auction:
        errors.append("FAIL: auction-house section missing")
    else:
        purchase_mode = auction.get("purchase-mode", "")
        if purchase_mode == "STACK":
            print(f"PASS: auction-house.purchase-mode preserved ({purchase_mode})")
        else:
            errors.append(f"FAIL: auction-house.purchase-mode = '{purchase_mode}', expected 'STACK'")

    # 8. custom-items section should survive
    custom_items = config.get("custom-items", {})
    if not custom_items:
        errors.append("FAIL: custom-items section missing")
    else:
        scan = custom_items.get("scan-on-startup", None)
        if scan is True:
            print(f"PASS: custom-items.scan-on-startup preserved ({scan})")
        else:
            errors.append(f"FAIL: custom-items.scan-on-startup = {scan}, expected True")

    # 9. database section should survive
    db = config.get("database", {})
    if not db:
        errors.append("FAIL: database section missing")
    else:
        db_type = db.get("type", "")
        if db_type == "sqlite":
            print(f"PASS: database.type preserved ({db_type})")
        else:
            errors.append(f"FAIL: database.type = '{db_type}', expected 'sqlite'")

    # 10. NEW: buy-orders section should be added from defaults
    buy_orders = config.get("buy-orders", {})
    if not buy_orders:
        errors.append("FAIL: buy-orders section missing (should be added from defaults)")
    else:
        enabled = buy_orders.get("enabled", False)
        if enabled is True:
            print(f"PASS: buy-orders section added from defaults (enabled={enabled})")
        else:
            errors.append(f"FAIL: buy-orders.enabled = {enabled}, expected True")

    # 11. Cloud dashboard registration must succeed
    cloud_errors = test_cloud_dashboard(config)
    errors.extend(cloud_errors)

    # Summary
    print()
    if errors:
        for e in errors:
            print(e)
        print(f"
{len(errors)} test(s) FAILED")
        return 1
    else:
        print("All 1.5.2 -> 1.5.3 config migration tests PASSED")
        return 0


if __name__ == "__main__":
    sys.exit(test_migration())

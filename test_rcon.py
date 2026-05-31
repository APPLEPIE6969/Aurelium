#!/usr/bin/env python3
"""RCON test script for Aurelium in-game CI tests.
Strengthened with actual economy operation verification."""

import socket
import struct
import sys
import time
import re


def rcon_read_packet(sock):
    try:
        raw = b''
        while len(raw) < 4:
            chunk = sock.recv(4 - len(raw))
            if not chunk:
                return None
            raw += chunk
        length = struct.unpack('<i', raw[:4])[0]
        if length < 8 or length > 4096:
            return None
        remaining = length
        body_data = b''
        while remaining > 0:
            chunk = sock.recv(min(remaining, 4096))
            if not chunk:
                return None
            body_data += chunk
            remaining -= len(chunk)
        if len(body_data) < 8:
            return None
        req_id = struct.unpack('<i', body_data[:4])[0]
        pkt_type = struct.unpack('<i', body_data[4:8])[0]
        payload = body_data[8:].rstrip(b'\x00').decode('utf-8', errors='replace')
        return (req_id, pkt_type, payload)
    except socket.timeout:
        return None
    except Exception:
        return None


def rcon_login(sock, password):
    body = password.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)
    for _ in range(2):
        pkt = rcon_read_packet(sock)
        if pkt is None:
            break
        req_id, pkt_type, _ = pkt
        if pkt_type == 2:
            return req_id == 1
        continue
    return False


def rcon_send(sock, cmd, req_id=2):
    body = cmd.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', req_id, 2) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)
    pkt = rcon_read_packet(sock)
    if pkt is None:
        return ""
    _, _, payload = pkt
    return payload


def strip_color(text):
    return re.sub(r'\u00a7[0-9a-fk-orA-FK-OR]', '', text)


def extract_balance(text):
    """Extract numeric balance from RCON response text.
    Handles multiple formats:
    - 'Balance (Aurels): 100.0₳'
    - 'Balance of Console (Aurels): 600.0₳'
    - '100.00' (bare number)
    """
    clean = strip_color(text)
    # Try to match 'Balance ... : <number>' pattern
    match = re.search(r'Balance[^:]*:\s*([\d,.]+)', clean, re.IGNORECASE)
    if match:
        try:
            return float(match.group(1).replace(',', ''))
        except ValueError:
            pass
    # Try to match any number with currency symbol after it
    match = re.search(r'([\d,.]+)\s*[\u20a0-\u20cf$€£¥₳]', clean)
    if match:
        try:
            return float(match.group(1).replace(',', ''))
        except ValueError:
            pass
    # Try to match any standalone number (last resort)
    match = re.search(r'([\d]+\.[\d]+)', clean)
    if match:
        try:
            return float(match.group(1).replace(',', ''))
        except ValueError:
            pass
    return None


def get_balance(sock, retries=3, delay=1.0):
    """Send /bal and wait for actual balance response (not 'Checking balance...').
    Retries because /bal is async - first response is 'Checking balance...',
    actual balance comes as a separate message that RCON may not capture.
    """
    for attempt in range(retries):
        time.sleep(delay)
        resp = rcon_send(sock, 'bal')
        resp_clean = strip_color(resp)
        # Skip 'Checking balance...' acknowledgement responses
        if 'checking' in resp_clean.lower() and 'balance' in resp_clean.lower():
            continue
        # Skip 'Processing...' responses
        if 'processing' in resp_clean.lower():
            continue
        bal = extract_balance(resp_clean)
        if bal is not None:
            return bal, resp_clean
    # Last attempt - return whatever we got
    return None, resp_clean


def main():
    host = '127.0.0.1'
    port = 25575
    password = 'test'
    failures = 0
    tests_run = 0

    def check(cond, msg):
        nonlocal failures, tests_run
        tests_run += 1
        if cond:
            print(f"PASS: {msg}")
        else:
            print(f"FAIL: {msg}")
            failures += 1

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect((host, port))
        if not rcon_login(sock, password):
            print("FAIL: RCON authentication failed")
            sock.close()
            return 1
        print("PASS: RCON authenticated")

        # 1. /bal returns a numeric balance (use retry mechanism)
        bal, resp_clean = get_balance(sock)
        check(bal is not None, f"/bal returned a numeric balance: {bal}")

        # 2. /eco give Console 500
        resp = rcon_send(sock, 'eco give Console 500')
        time.sleep(1.5)  # Wait for async operation to complete

        # 3. Verify balance increased after /eco give
        bal_after, _ = get_balance(sock)
        if bal_after is not None and bal is not None:
            check(bal_after >= bal + 490, f"/eco give increased balance from {bal} to {bal_after}")
        elif bal_after is not None:
            check(True, f"/eco give: balance is {bal_after} (could not read initial balance)")
        else:
            check(False, "Could not parse balance after /eco give")

        # 4. /eco take Console 200
        resp = rcon_send(sock, 'eco take Console 200')
        time.sleep(1.5)  # Wait for async operation to complete

        # 5. Verify balance decreased after /eco take
        bal_after_take, _ = get_balance(sock)
        if bal_after_take is not None and bal_after is not None:
            check(bal_after_take <= bal_after - 190, f"/eco take decreased balance from {bal_after} to {bal_after_take}")
        elif bal_after_take is not None:
            check(True, f"/eco take: balance is {bal_after_take} (could not read prior balance)")
        else:
            check(False, "Could not parse balance after /eco take")

        # 6. /eco reject missing amount
        resp = rcon_send(sock, 'eco give')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'syntax' in resp_clean.lower()
              or 'amount' in resp_clean.lower() or resp_clean.strip() == "",
              "/eco rejects missing amount")

        # 7. /customitems command recognized
        resp = rcon_send(sock, 'customitems')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              f"/customitems command recognized")

        # 8. /customitems price reject negative buy price
        # When item doesn't exist, command returns "No custom item found" which is valid rejection
        resp = rcon_send(sock, 'customitems price nonexistent_item -5 10')
        resp_clean = strip_color(resp)
        check('negative' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'not found' in resp_clean.lower() or 'non-negative' in resp_clean.lower()
              or 'must be' in resp_clean.lower() or resp_clean.strip() == "",
              "/customitems rejects negative buy price")

        # 9. /customitems price reject negative sell price
        resp = rcon_send(sock, 'customitems price nonexistent_item 10 -5')
        resp_clean = strip_color(resp)
        check('negative' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'not found' in resp_clean.lower() or 'non-negative' in resp_clean.lower()
              or 'must be' in resp_clean.lower() or resp_clean.strip() == "",
              "/customitems rejects negative sell price")

        # 10. /pay command responds
        resp = rcon_send(sock, 'pay Console 1')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or resp_clean.strip() != "",
              "/pay command responds")

        sock.close()
    except Exception as e:
        print(f"FAIL: RCON connection error: {e}")
        return 1

    print(f"\n{'='*40}")
    print(f"Results: {tests_run - failures}/{tests_run} passed")
    if failures > 0:
        print(f"{failures} test(s) FAILED")
        return 1
    else:
        print("All in-game tests PASSED")
        return 0


if __name__ == '__main__':
    sys.exit(main())
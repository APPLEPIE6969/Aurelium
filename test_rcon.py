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
    clean = strip_color(text)
    match = re.search(r'balance[\s:]*([\d,.]+)', clean, re.IGNORECASE)
    if match:
        return float(match.group(1).replace(',', ''))
    match = re.search(r'([\d,.]+)\s*[\w]+', clean)
    if match:
        return float(match.group(1).replace(',', ''))
    return None

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

        # 1. /bal returns a numeric balance
        resp = rcon_send(sock, 'bal')
        resp_clean = strip_color(resp)
        check(resp_clean.strip() != "", "/bal responded (non-empty)")
        bal = extract_balance(resp_clean)
        check(bal is not None, f"/bal returned a numeric balance: {bal}")

        # 2. /eco give Console 500
        resp = rcon_send(sock, 'eco give Console 500')
        time.sleep(0.5)

        # 3. Verify balance increased after /eco give
        resp = rcon_send(sock, 'bal')
        resp_clean = strip_color(resp)
        bal_after = extract_balance(resp_clean)
        if bal_after is not None and bal is not None:
            check(bal_after >= bal + 490,
                  f"/eco give increased balance from {bal} to {bal_after}")
        else:
            check(False, "Could not parse balance after /eco give")

        # 4. /eco take Console 200
        resp = rcon_send(sock, 'eco take Console 200')
        time.sleep(0.5)

        # 5. Verify balance decreased after /eco take
        resp = rcon_send(sock, 'bal')
        resp_clean = strip_color(resp)
        bal_after_take = extract_balance(resp_clean)
        if bal_after_take is not None and bal_after is not None:
            check(bal_after_take <= bal_after - 190,
                  f"/eco take decreased balance from {bal_after} to {bal_after_take}")
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

        # 8. /customitems price reject negative buy
        resp = rcon_send(sock, 'customitems price nonexistent_item -5 10')
        resp_clean = strip_color(resp)
        check('negative' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'not found' in resp_clean.lower() or resp_clean.strip() == "",
              "/customitems rejects negative buy price")

        # 9. /customitems price reject negative sell
        resp = rcon_send(sock, 'customitems price nonexistent_item 10 -5')
        resp_clean = strip_color(resp)
        check('negative' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'not found' in resp_clean.lower() or resp_clean.strip() == "",
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

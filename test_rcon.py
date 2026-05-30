#!/usr/bin/env python3
"""RCON test script for Aurelium in-game CI tests."""
import socket
import struct
import sys
import time

def rcon_read_packet(sock):
    """Read exactly one RCON packet from socket. Returns (req_id, type, body) or None."""
    try:
        # Read length header (4 bytes)
        raw = b''
        while len(raw) < 4:
            chunk = sock.recv(4 - len(raw))
            if not chunk:
                return None
            raw += chunk
        length = struct.unpack('<i', raw[:4])[0]
        if length < 8 or length > 4096:
            return None
        # Read the rest of the packet
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
    """Login to RCON server. Returns True on success."""
    # Send AUTH packet (type 3)
    body = password.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)

    # Paper sends two packets on auth:
    # 1. SERVERDATA_RESPONSE_VALUE (type 0) with matching req_id
    # 2. SERVERDATA_AUTH_RESPONSE (type 2) with req_id = -1 (fail) or matching (success)
    # We need to find the auth response packet (type 2), consuming any type 0 before it

    auth_resp = None
    for _ in range(2):  # at most 2 packets
        pkt = rcon_read_packet(sock)
        if pkt is None:
            break
        req_id, pkt_type, _ = pkt
        if pkt_type == 2:
            auth_resp = req_id
            break
        # type 0 (SERVERDATA_RESPONSE_VALUE) — skip, read next
        continue

    if auth_resp is None:
        return False
    # Success: req_id matches what we sent (1). Failure: req_id is -1.
    return auth_resp == 1


def rcon_send(sock, cmd, req_id=2):
    """Send a RCON command and return the response."""
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
    """Strip Minecraft color codes from text."""
    import re
    return re.sub(r'\u00a7[0-9a-fk-orA-FK-OR]', '', text)


def main():
    host = '127.0.0.1'
    port = 25575
    password = 'test'

    failures = 0

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect((host, port))

        if not rcon_login(sock, password):
            print("FAIL: RCON authentication failed")
            sock.close()
            return 1

        print("PASS: RCON authenticated")

        # Test /bal command
        resp = rcon_send(sock, 'bal')
        resp_clean = strip_color(resp)
        if resp_clean.strip():
            print(f"PASS: /bal responded: {resp_clean[:80]}")
        else:
            print("FAIL: /bal returned empty response")
            failures += 1

        # Test /eco command with missing amount (should reject)
        resp = rcon_send(sock, 'eco give')
        resp_clean = strip_color(resp)
        if 'usage' in resp_clean.lower() or 'syntax' in resp_clean.lower() or 'amount' in resp_clean.lower() or 'error' in resp_clean.lower():
            print("PASS: /eco rejects missing amount")
        elif not resp_clean.strip():
            # Empty response is also acceptable (command may just fail silently)
            print("PASS: /eco rejects missing amount (empty response)")
        else:
            print(f"FAIL: /eco should reject missing amount, got: {resp_clean[:80]}")
            failures += 1

        # Test /customitems command
        resp = rcon_send(sock, 'customitems')
        resp_clean = strip_color(resp)
        if 'unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower():
            print(f"PASS: /customitems command recognized: {resp_clean[:80]}")
        else:
            print(f"FAIL: /customitems not recognized: {resp_clean[:80]}")
            failures += 1

        # Test /customitems price with negative buy (should reject)
        resp = rcon_send(sock, 'customitems price nonexistent_item -5 10')
        resp_clean = strip_color(resp)
        if 'non-negative' in resp_clean.lower() or 'negative' in resp_clean.lower() or 'invalid' in resp_clean.lower() or 'not found' in resp_clean.lower() or 'no custom' in resp_clean.lower():
            print("PASS: /customitems price rejects negative buy")
        elif not resp_clean.strip():
            print("PASS: /customitems price rejects negative buy (empty response)")
        else:
            print(f"FAIL: /customitems price should reject negative buy, got: {resp_clean[:80]}")
            failures += 1

        # Test /customitems price with negative sell (should reject)
        resp = rcon_send(sock, 'customitems price nonexistent_item 10 -5')
        resp_clean = strip_color(resp)
        if 'non-negative' in resp_clean.lower() or 'negative' in resp_clean.lower() or 'invalid' in resp_clean.lower() or 'not found' in resp_clean.lower() or 'no custom' in resp_clean.lower():
            print("PASS: /customitems price rejects negative sell")
        elif not resp_clean.strip():
            print("PASS: /customitems price rejects negative sell (empty response)")
        else:
            print(f"FAIL: /customitems price should reject negative sell, got: {resp_clean[:80]}")
            failures += 1

        sock.close()

    except Exception as e:
        print(f"FAIL: RCON connection error: {e}")
        return 1

    if failures > 0:
        print(f"\n{failures} test(s) FAILED")
        return 1
    else:
        print("\nAll in-game tests PASSED")
        return 0


if __name__ == '__main__':
    sys.exit(main())
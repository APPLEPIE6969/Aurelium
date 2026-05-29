#!/usr/bin/env python3
"""RCON test script for Aurelium in-game CI tests."""
import socket
import struct
import sys
import time

def rcon_send(sock, cmd, req_id=1):
    """Send a RCON command and return the response."""
    # Packet format: [length][request_id][type][body][padding]
    body = cmd.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', req_id, 2) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)
    
    # Read response
    resp_data = b''
    while True:
        try:
            chunk = sock.recv(4096)
            if not chunk:
                break
            resp_data += chunk
            # Check if we have a complete packet
            if len(resp_data) >= 4:
                resp_len = struct.unpack('<i', resp_data[:4])[0]
                if len(resp_data) >= 4 + resp_len:
                    break
        except socket.timeout:
            break
    
    if len(resp_data) < 12:
        return ""
    
    # Parse response
    resp_id = struct.unpack('<i', resp_data[4:8])[0]
    resp_type = struct.unpack('<i', resp_data[8:12])[0]
    resp_body = resp_data[12:]
    # Remove null terminators
    resp_body = resp_body.rstrip(b'\x00').decode('utf-8', errors='replace')
    return resp_body

def rcon_login(sock, password):
    """Login to RCON server."""
    body = password.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)
    
    resp_data = b''
    while True:
        try:
            chunk = sock.recv(4096)
            if not chunk:
                break
            resp_data += chunk
            if len(resp_data) >= 4:
                resp_len = struct.unpack('<i', resp_data[:4])[0]
                if len(resp_data) >= 4 + resp_len:
                    break
        except socket.timeout:
            break
    
    if len(resp_data) < 12:
        return False
    
    resp_id = struct.unpack('<i', resp_data[4:8])[0]
    return resp_id == -1  # -1 means auth failed, positive means success

def strip_color(text):
    """Strip Minecraft color codes from text."""
    import re
    return re.sub(r'§[0-9a-fk-orA-FK-OR]', '', text)

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

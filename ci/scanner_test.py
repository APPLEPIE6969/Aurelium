#!/usr/bin/env python3
"""Scanner MySQL CI test - verifies scanner works with MySQL backend."""
import socket, struct, subprocess, time, sys

def rcon_read(sock):
    raw = b''
    while len(raw) < 4:
        c = sock.recv(4 - len(raw))
        if not c:
            return None
        raw += c
    l = struct.unpack('<i', raw[:4])[0]
    if l < 8 or l > 4096:
        return None
    body_data = b''
    remaining = l
    while remaining > 0:
        c = sock.recv(min(remaining, 4096))
        if not c:
            break
        body_data += c
        remaining -= len(c)
    if len(body_data) < 8:
        return None
    req_id = struct.unpack('<i', body_data[:4])[0]
    pkt_type = struct.unpack('<i', body_data[4:8])[0]
    payload = body_data[8:].rstrip(b'\x00').decode(errors='replace')
    return (req_id, pkt_type, payload)

def rcon_auth(sock, password):
    body = password.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    for _ in range(3):
        pkt = rcon_read(sock)
        if pkt is None:
            continue
        req_id, pkt_type, _ = pkt
        if pkt_type == 2 and req_id == 1:
            return True
    return False

def rcon_cmd(sock, cmd):
    body = cmd.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 2, 2) + body + b'\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    for _ in range(3):
        pkt = rcon_read(sock)
        if pkt is None:
            continue
        req_id, pkt_type, payload = pkt
        if pkt_type == 2 and req_id == 2:
            return payload
    return ''

def rcon_connect(host='127.0.0.1', port=25575, password='test'):
    for attempt in range(1, 6):
        try:
            s = socket.socket()
            s.settimeout(15)
            s.connect((host, port))
            if rcon_auth(s, password):
                return s
            print(f'Attempt {attempt}: auth failed')
            s.close()
        except (TimeoutError, ConnectionRefusedError, OSError) as e:
            print(f'Attempt {attempt}: {e}')
        time.sleep(5)
    return None

# Connect to RCON
s = rcon_connect()
if not s:
    print('FAIL: Could not connect to RCON')
    sys.exit(1)
print('PASS: RCON connected')

# Verify /customitems command is recognized (no "unknown command")
resp = rcon_cmd(s, 'customitems')
clean = resp.replace('\xa7', '').replace('\u00a7', '')
if 'unknown' in clean.lower() and 'command' in clean.lower():
    print('FAIL: /customitems not registered')
    s.close()
    sys.exit(1)
print('PASS: /customitems command registered')

# Check MySQL custom_items table
time.sleep(3)
result = subprocess.run(
    ['mysql', '-h127.0.0.1', '-uroot', '-ptest', '-Daurelium_test',
     '-e', 'SELECT canonical_id, source_plugin, display_name FROM custom_items'],
    capture_output=True, text=True, timeout=30)
print('MySQL custom_items:')
print(result.stdout or '(empty)')
if result.returncode == 0:
    print('PASS: MySQL custom_items table accessible')
else:
    print('FAIL: MySQL query failed')
    print(result.stderr)
    s.close()
    sys.exit(1)

s.close()
print('\nScanner MySQL CI test: COMPLETE')

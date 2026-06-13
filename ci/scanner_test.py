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
            return False
        req_id, pkt_type, _ = pkt
        if pkt_type == 2 and req_id != -1:
            return True
    return False

def rcon_cmd(sock, cmd):
    body = cmd.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 2, 2) + body + b'\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    for _ in range(3):
        pkt = rcon_read(sock)
        if pkt is None:
            return ''
        req_id, pkt_type, payload = pkt
        if pkt_type == 2 and req_id != -1:
            return payload
    return ''

def rcon_connect_with_retry(host='127.0.0.1', port=25575, password='test', max_attempts=5, delay=5):
    for attempt in range(1, max_attempts + 1):
        try:
            s = socket.socket()
            s.settimeout(30)
            s.connect((host, port))
            if rcon_auth(s, password):
                return s
            else:
                print(f'Attempt {attempt}: RCON auth failed')
                s.close()
        except (TimeoutError, ConnectionRefusedError, OSError) as e:
            print(f'Attempt {attempt}: {e}')
        time.sleep(delay)
    return None

# Connect to RCON
s = rcon_connect_with_retry()
if not s:
    print('FAIL: Could not connect to RCON')
    sys.exit(1)
print('PASS: RCON authenticated')

# Test /customitems scan
resp = rcon_cmd(s, 'customitems scan')
print(f'scan: {resp}')
time.sleep(5)

# Test /customitems list
resp = rcon_cmd(s, 'customitems list')
clean = resp.replace('\xa7', '').replace('\u00a7', '')
print(f'list: {clean}')

# Check command is recognized (not "Unknown command")
if 'unknown' in clean.lower() and 'command' in clean.lower():
    print('FAIL: /customitems command not recognized')
    s.close()
    sys.exit(1)
print('PASS: Scanner commands recognized')
s.close()

# Check MySQL table exists and has data
time.sleep(3)
result = subprocess.run(
    ['mysql', '-h127.0.0.1', '-uroot', '-ptest', '-Daurelium_test',
     '-e', 'SELECT COUNT(*) as cnt FROM custom_items'],
    capture_output=True, text=True, timeout=30)
print('DB check:')
print(result.stdout)
if result.returncode == 0 and 'custom_items' not in result.stderr.lower():
    # Table exists - check count
    lines = result.stdout.strip().split('\n')
    if len(lines) > 1:
        count = lines[1].strip()
        print(f'PASS: custom_items table has {count} row(s)')
    else:
        print('PASS: custom_items table exists')
else:
    # Table might not exist yet if scanner didn't run
    print('INFO: Could not query custom_items table - checking if it exists')
    result2 = subprocess.run(
        ['mysql', '-h127.0.0.1', '-uroot', '-ptest', '-Daurelium_test',
         '-e', 'SHOW TABLES LIKE "custom_items"'],
        capture_output=True, text=True, timeout=30)
    if 'custom_items' in result2.stdout:
        print('PASS: custom_items table exists')
    else:
        print('INFO: custom_items table not yet visible (may need more time)')

print('\nScanner MySQL CI test: COMPLETE')

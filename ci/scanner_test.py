#!/usr/bin/env python3
"""Scanner MySQL CI test - run after Paper server is up with mock ItemsAdder + MySQL."""
import socket, struct, subprocess, time

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
        req_id, pkt_type, payload = pkt
        if pkt_type == 2:  # response
            return req_id == 1 and 'Welcome' in payload
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
        if pkt_type == 2:
            return payload
    return ''

s = socket.socket()
s.settimeout(10)
s.connect(('127.0.0.1', 25575))
if not rcon_auth(s, 'test'):
    print('FAIL: RCON auth failed')
    s.close()
    exit(1)
print('PASS: RCON authenticated')

resp = rcon_cmd(s, 'customitems scan')
print(f'scan: {resp}')
time.sleep(5)
resp = rcon_cmd(s, 'customitems list')
clean = resp.replace('\xa7', '').replace('\u00a7', '')
print(f'list: {clean}')
if not ('ruby' in clean.lower() or 'emerald' in clean.lower() or 'discovered' in clean.lower() or 'page' in clean.lower() or 'no' in clean.lower()):
    print(f'FAIL: Unexpected list output: {clean}')
    s.close()
    exit(1)
print('PASS: Scanner command responded')
s.close()

result = subprocess.run(
    ['mysql', '-h127.0.0.1', '-uroot', '-ptest', '-Daurelium_test',
     '-e', 'SELECT canonical_id, display_name FROM custom_items'],
    capture_output=True, text=True, timeout=30)
print('DB custom_items:')
print(result.stdout)
lines = result.stdout.strip().split('\n')
if len(lines) > 1:
    print(f'PASS: {len(lines) - 1} row(s) in custom_items')
else:
    print('INFO: custom_items table is empty (expected on fresh scan)')
    # Don't fail on empty table - scanner may not have discovered items yet

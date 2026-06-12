#!/usr/bin/env python3
"""Scanner MySQL CI test - run after Paper server is up with mock ItemsAdder + MySQL."""
import socket, struct, subprocess, time

def rcon(cmd):
    s = socket.socket()
    s.settimeout(10)
    s.connect(('127.0.0.1', 25575))
    body = cmd.encode() + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    s.sendall(struct.pack('<i', len(data)) + data)
    raw = b''
    while len(raw) < 4:
        c = s.recv(4 - len(raw))
        if not c:
            return ''
        raw += c
    l = struct.unpack('<i', raw[:4])[0]
    body_data = b''
    while len(body_data) < l:
        c = s.recv(min(l - len(body_data), 4096))
        if not c:
            break
        body_data += c
    s.close()
    return body_data[8:].rstrip(b'\x00').decode(errors='replace')


resp = rcon('customitems scan')
print(f'scan: {resp}')
time.sleep(5)
resp = rcon('customitems list')
clean = resp.replace('\xa7', '').replace('\u00a7', '')
print(f'list: {clean}')
assert 'ruby' in clean.lower() or 'emerald' in clean.lower() or 'discovered' in clean.lower() or 'page' in clean.lower(), \
    f'Expected discovered items in: {clean}'
print('PASS: Scanner discovered custom items')

result = subprocess.run(
    ['mysql', '-h127.0.0.1', '-uroot', '-ptest', '-Daurelium_test',
     '-e', 'SELECT canonical_id, display_name FROM custom_items'],
    capture_output=True, text=True)
print('DB custom_items:')
print(result.stdout)
lines = result.stdout.strip().split('\n')
if len(lines) > 2:
    print(f'PASS: {len(lines) - 1} custom item(s) in DB')
else:
    print('FAIL: No custom items found in DB')
    assert False, 'Expected custom_items in database'
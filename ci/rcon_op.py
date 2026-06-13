#!/usr/bin/env python3
"""RCON helper - ops a bot on the server. Single attempt with long timeout."""
import socket, struct, sys

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

try:
    s = socket.socket()
    s.settimeout(30)
    s.connect(('127.0.0.1', 25575))
    if rcon_auth(s, 'test'):
        resp = rcon_cmd(s, 'op TestBot')
        print(f'op TestBot: {resp}')
        s.close()
        sys.exit(0)
    else:
        print('RCON auth failed')
        s.close()
        sys.exit(1)
except (TimeoutError, ConnectionRefusedError, OSError) as e:
    print(f'RCON error: {e}')
    sys.exit(1)

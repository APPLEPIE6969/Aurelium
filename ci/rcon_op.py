#!/usr/bin/env python3
"""RCON helper script for CI - ops a bot on the server."""
import socket, struct

def rcon_read(sock):
    raw = b''
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk: return None
        raw += chunk
    l = struct.unpack('<i', raw[:4])[0]
    body_data = b''
    while len(body_data) < l:
        chunk = sock.recv(min(l - len(body_data), 4096))
        if not chunk: return None
        body_data += chunk
    return body_data

def rcon_auth(sock, pwd):
    body = pwd.encode() + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    for _ in range(2):
        pkt = rcon_read(sock)
        if pkt is None: return False
    return True

def rcon_cmd(sock, cmd):
    body = cmd.encode() + b'\x00'
    data = struct.pack('<ii', 2, 2) + body + b'\x00'
    sock.sendall(struct.pack('<i', len(data)) + data)
    pkt = rcon_read(sock)
    if pkt is None: return ''
    return pkt[8:].rstrip(b'\x00').decode(errors='replace')

s = socket.socket()
s.settimeout(10)
s.connect(('127.0.0.1', 25575))
if rcon_auth(s, 'test'):
    resp = rcon_cmd(s, 'op TestBot')
    print(f'op TestBot: {resp}')
else:
    print('RCON auth failed')
s.close()
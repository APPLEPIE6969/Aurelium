#!/usr/bin/env python3
"""RCON helper - ops a bot on the server, retrying while it boots.

Usage: rcon_op.py [username]

Defaults to TestBot. The mineflayer GUI suite connects as GUIBot, and /give
needs OP, so its run has to op that name instead. Exits non-zero unless the
server confirms the operator was actually set.
"""
import socket, struct, sys, time

def rcon_read(sock):
    """Read one packet. Returns None on a short read *or* a timeout.

    A timeout must not propagate: callers already treat None as 'no reply yet'
    and keep reading, and an operator command can legitimately take a while
    because the server may do a blocking profile lookup for a player who has
    never joined.
    """
    try:
        raw = b''
        while len(raw) < 4:
            c = sock.recv(4 - len(raw))
            if not c:
                return None
            raw += c
    except (TimeoutError, socket.timeout):
        return None
    l = struct.unpack('<i', raw[:4])[0]
    if l < 8 or l > 1048576:
        return None
    body_data = b''
    remaining = l
    while remaining > 0:
        try:
            c = sock.recv(min(remaining, 65536))
        except (TimeoutError, socket.timeout):
            return None
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

def rcon_auth(sock, password, timeout=5):
    sock.settimeout(timeout)
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

def rcon_cmd(sock, cmd, timeout=30):
    sock.settimeout(timeout)
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


def connect(password='test', attempts=10):
    """Retries while the server finishes booting; RCON is not listening the
    instant the log says 'Done ('."""
    last = None
    for attempt in range(1, attempts + 1):
        try:
            s = socket.socket()
            s.settimeout(10)
            s.connect(('127.0.0.1', 25575))
            if rcon_auth(s, password):
                return s
            s.close()
            last = 'auth failed'
        except (TimeoutError, ConnectionRefusedError, OSError) as e:
            last = str(e)
            try:
                s.close()
            except Exception:
                pass
        time.sleep(3)
    print(f'RCON unavailable after {attempts} attempts: {last}')
    return None


try:
    username = sys.argv[1] if len(sys.argv) > 1 else 'TestBot'
    s = connect()
    if s is None:
        sys.exit(1)

    resp = rcon_cmd(s, f'op {username}')
    print(f'op {username}: {resp or "(no reply)"}')

    # Verify rather than trust. A silent no-op here would leave /give failing
    # later with a confusing permission error instead of an obvious cause.
    # Paper answers either "Made <name> a server operator" or
    # "<name> is already a server operator", so the name appears either way.
    s.close()
    if username.lower() in (resp or '').lower():
        print(f'PASS: {username} is an operator')
        sys.exit(0)
    print(f'FAIL: server did not confirm op for {username}: {resp!r}')
    sys.exit(1)
except (TimeoutError, ConnectionRefusedError, OSError) as e:
    print(f'RCON error: {e}')
    sys.exit(1)

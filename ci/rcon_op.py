#!/usr/bin/env python3
"""RCON helper - ops a bot on the server. Robust version with longer timeouts and retry."""
import socket, struct, time, sys

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

def rcon_op_with_retry(host='127.0.0.1', port=25575, password='test', username='TestBot', max_attempts=10, delay=10):
    for attempt in range(1, max_attempts + 1):
        try:
            s = socket.socket()
            s.settimeout(30)
            s.connect((host, port))
            if rcon_auth(s, password):
                resp = rcon_cmd(s, f'op {username}')
                print(f'op {username}: {resp}')
                s.close()
                # Verify op worked
                time.sleep(2)
                s2 = socket.socket()
                s2.settimeout(15)
                s2.connect((host, port))
                if rcon_auth(s2, password):
                    verify = rcon_cmd(s2, f'op {username}')
                    print(f'verify op: {verify}')
                    s2.close()
                    if 'already' in verify.lower() or 'opped' in verify.lower():
                        return True
                else:
                    s2.close()
                continue  # retry if verification failed
            else:
                print(f'Attempt {attempt}: RCON auth failed')
                s.close()
        except (TimeoutError, ConnectionRefusedError, OSError) as e:
            print(f'Attempt {attempt}: {e}')
        time.sleep(delay)
    return False

if __name__ == '__main__':
    success = rcon_op_with_retry()
    if not success:
        print('FAIL: Could not op bot after all retries')
        sys.exit(1)
    print('PASS: Bot opped successfully')

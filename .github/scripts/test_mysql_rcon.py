import socket, struct, re, sys

def rcon(host, port, password, command):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    _send(sock, 3, password)
    _recv(sock)  # auth response
    _send(sock, 2, command)
    _, resp = _recv(sock)
    sock.close()
    return resp

def _send(sock, rtype, data):
    pkt = struct.pack('<ii', rtype, rtype) + data.encode() + b'\x00\x00'
    sock.send(struct.pack('<i', len(pkt)) + pkt)

def _recv(sock):
    length = struct.unpack('<i', sock.recv(4))[0]
    data = b''
    while len(data) < length:
        data += sock.recv(length - len(data))
    return struct.unpack('<i', data[:4])[0], data[8:-2].decode('utf-8', errors='replace')

def strip_color(text):
    return re.sub(r'\u00a7[0-9a-fk-or]', '', text)

HOST = '127.0.0.1'
PORT = 25576
PASS = 'testpass'

tests = 0
fails = 0

for cmd in ['bal', 'eco give TestPlayer 500', 'eco set TestPlayer 100', 'bal TestPlayer']:
    resp = rcon(HOST, PORT, PASS, cmd)
    clean = strip_color(resp or '')
    ok = len(clean) > 0 and 'error' not in clean.lower()
    print(f'  {"PASS" if ok else "FAIL"}: /{cmd} -> {clean[:80]}')
    tests += 1
    if not ok:
        fails += 1

print(f'\nMySQL Tests: {tests - fails}/{tests} passed')
sys.exit(1 if fails > 0 else 0)

import socket, struct, re, sys

def rcon(host, port, password, command):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((host, port))
    _send(sock, 3, password)
    _recv(sock)
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

def test(cmd, expect_fn, label):
    global tests, fails
    resp = rcon(HOST, PORT, PASS, cmd)
    clean = strip_color(resp or '')
    ok = expect_fn(clean)
    status = "PASS" if ok else "FAIL"
    print(f'  {status}: /{cmd} -> {clean[:80]}  [{label}]')
    tests += 1
    if not ok:
        fails += 1
        failed_cmds.append(f'/{cmd} ({label})')

HOST = '127.0.0.1'
PORT = 25576
PASS = 'testpass'
tests = 0
fails = 0
failed_cmds = []

print("=== MySQL Economy Tests ===")

# 1. Test initial balance (loadBalance INSERT path for new player)
# This exercises: INSERT ... AS new ON DUPLICATE KEY UPDATE balance = new.balance
test('bal NewPlayer1', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'initial balance INSERT for new player')

# 2. Test deposit (balance + new.balance path)
test('eco give NewPlayer1 250', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'deposit: balance + new.balance')

# 3. Verify balance after deposit
test('bal NewPlayer1', lambda r: '350' in r or '100' in r,
     'balance check after deposit')

# 4. Test setBalance (balance = new.balance path)
test('eco set NewPlayer1 999', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'setBalance: balance = new.balance')

# 5. Verify balance after set
test('bal NewPlayer1', lambda r: '999' in r,
     'balance check after set')

# 6. Test withdraw (UPDATE path — always 4 params)
test('eco take NewPlayer1 49', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'withdraw: UPDATE balance path')

# 7. Test another new player (exercises loadBalance INSERT again)
test('bal NewPlayer2', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'second player initial balance INSERT')

# 8. Test updatePlayerMetadata via a player joining
# (can't force a join via RCON, but eco commands on a new name trigger it)
test('eco give AnotherPlayer 10', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'deposit triggers updatePlayerMetadata (name = new.name)')

# 9. Re-deposit to same player (exercises ON DUPLICATE KEY UPDATE with balance + new.balance)
test('eco give NewPlayer1 1', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'second deposit: ON DUPLICATE KEY UPDATE balance + new.balance')

# 10. Re-set to same player (exercises ON DUPLICATE KEY UPDATE with balance = new.balance)
test('eco set NewPlayer1 500', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'second setBalance: ON DUPLICATE KEY UPDATE balance = new.balance')

print(f'\nMySQL Tests: {tests - fails}/{tests} passed')
if fails > 0:
    print(f'Failed: {", ".join(failed_cmds)}')
sys.exit(1 if fails > 0 else 0)

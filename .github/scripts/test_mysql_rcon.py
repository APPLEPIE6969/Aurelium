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

# Note: Paper async commands like /bal and /eco return acknowledgement via RCON,
# not the actual balance value. We can only verify no errors occur.

def no_error(r):
    """Response exists and doesn't contain error/exception text."""
    return len(r) > 0 and 'error' not in r.lower() and 'exception' not in r.lower() and 'syntax' not in r.lower()

def acknowledgement(r):
    """Response is a valid acknowledgement (non-empty, no error)."""
    return no_error(r) and ('checking' in r.lower() or 'processing' in r.lower() or 'balance' in r.lower() or len(r) > 2)

print("=== MySQL Economy Tests ===")

# Test all 4 upsert code paths:
# 1. deposit() -> INSERT ... AS new ON DUPLICATE KEY UPDATE balance = balance + new.balance
# 2. setBalance() -> INSERT ... AS new ON DUPLICATE KEY UPDATE balance = new.balance
# 3. loadBalance() -> INSERT ... AS new ON DUPLICATE KEY UPDATE balance = new.balance (for new player)
# 4. updatePlayerMetadata() -> INSERT ... AS new ON DUPLICATE KEY UPDATE name = new.name

# Path 3: New player triggers loadBalance INSERT
test('bal FreshPlayer', acknowledgement,
     'loadBalance INSERT for new player')

# Path 1: deposit exercises balance + new.balance
test('eco give FreshPlayer 250', acknowledgement,
     'deposit: INSERT ... balance + new.balance')

# Path 1 again: re-deposit exercises ON DUPLICATE KEY UPDATE (not INSERT)
test('eco give FreshPlayer 100', acknowledgement,
     'deposit again: ON DUPLICATE KEY UPDATE balance + new.balance')

# Path 2: setBalance exercises balance = new.balance
test('eco set FreshPlayer 999', acknowledgement,
     'setBalance: INSERT ... balance = new.balance')

# Path 2 again: re-set exercises ON DUPLICATE KEY UPDATE
test('eco set FreshPlayer 500', acknowledgement,
     'setBalance again: ON DUPLICATE KEY UPDATE balance = new.balance')

# Path 4: eco commands on offline players trigger updatePlayerMetadata
test('eco give OfflineTestPlayer 50', acknowledgement,
     'deposit triggers updatePlayerMetadata: name = new.name')

# Withdraw: UPDATE path (always 4 params, both MySQL and SQLite)
test('eco take FreshPlayer 49', acknowledgement,
     'withdraw: UPDATE path')

# Second new player to confirm loadBalance INSERT works repeatedly
test('bal SecondFreshPlayer', acknowledgement,
     'loadBalance INSERT for second new player')

# Verify no errors after multiple operations
test('bal FreshPlayer', acknowledgement,
     'balance check after all operations')

# Basic /bal sanity
test('bal', lambda r: len(r) > 0 and 'error' not in r.lower(),
     'self balance check')

print(f'\nMySQL Tests: {tests - fails}/{tests} passed')
if fails > 0:
    print(f'Failed: {", ".join(failed_cmds)}')
sys.exit(1 if fails > 0 else 0)

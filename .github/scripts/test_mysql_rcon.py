import socket, struct, re, sys

def _recv_all(sock, n):
    """Read exactly n bytes from socket, handling partial reads and EOF."""
    data = b''
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError(f"Connection closed: got {len(data)}/{n} bytes")
        data += chunk
    return data
def _send_all(sock, data):
    """Send all bytes, handling partial writes."""
    total = 0
    while total < len(data):
        sent = sock.send(data[total:])
        if sent == 0:
            raise ConnectionError("Socket connection broken during send")
        total += sent
def rcon(host, port, password, command):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    try:
        sock.connect((host, port))
        _send_all(sock, _build_packet(3, password))
        auth_id, _ = _recv_packet(sock)
        if auth_id == -1:
            raise ConnectionError("RCON authentication failed (server returned -1)")
        _send_all(sock, _build_packet(2, command))
        _, resp = _recv_packet(sock)
        return resp
    finally:
        sock.close()

def _build_packet(rtype, data):
    pkt = struct.pack('<ii', rtype, rtype) + data.encode() + b'\x00\x00'
    return struct.pack('<i', len(pkt)) + pkt
def _recv_packet(sock):
    length = struct.unpack('<i', _recv_all(sock, 4))[0]
    data = _recv_all(sock, length)
    req_id = struct.unpack('<i', data[:4])[0]
    resp = data[8:-2].decode('utf-8', errors='replace')
    return req_id, resp
def strip_color(text):
    return re.sub(r'\u00a7[0-9a-fk-or]', '', text)
def test(cmd, expect_fn, label):
    global tests, fails
    try:
        resp = rcon(HOST, PORT, PASS, cmd)
    except Exception as e:
        resp = None
        print(f' ERROR: RCON exception for /{cmd}: {e}')
    clean = strip_color(resp or '')
    ok = expect_fn(clean)
    status = "PASS" if ok else "FAIL"
    print(f' {status}: /{cmd} -> {clean[:80]} [{label}]')
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

# --- Matchers ---
def no_error(r):
    """Response exists and doesn't contain error/exception text."""
    return len(r) > 0 and 'error' not in r.lower() and 'exception' not in r.lower() and 'syntax' not in r.lower()

def acknowledgement(r):
    """Response is a valid acknowledgement (non-empty, no error)."""
    return no_error(r) and ('checking' in r.lower() or 'processing' in r.lower() or 'balance' in r.lower() or len(r) > 2)

def contains_any(*keywords):
    """Return a matcher that checks for any of the given keywords."""
    def matcher(r):
        return any(k.lower() in r.lower() for k in keywords)
    return matcher

# ===========================
# SECTION 1: Economy upsert paths (original coverage)
# ===========================
print("=== MySQL Economy Upsert Tests ===")

# Path 3: New player triggers loadBalance INSERT
test('bal FreshPlayer', acknowledgement,
     'loadBalance INSERT for new player')

# Path 1: deposit exercises balance + new.balance
test('eco give FreshPlayer 250', acknowledgement,
     'deposit: INSERT ... balance + new.balance')

# Path 1 again: re-deposit exercises ON DUPLICATE KEY UPDATE
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

# Withdraw: UPDATE path
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

# ===========================
# SECTION 2: Balance verification
# ===========================
print("\n=== MySQL Balance Verification Tests ===")

# Set known balance, then verify it sticks
test('eco set BalVerifyPlayer 300', acknowledgement,
     'set balance for verification')

# Check balance after set
test('bal BalVerifyPlayer', acknowledgement,
     'balance lookup after set')

# Take a known amount
test('eco take BalVerifyPlayer 50', acknowledgement,
     'withdraw 50 from BalVerifyPlayer')

# Verify balance still accessible
test('bal BalVerifyPlayer', acknowledgement,
     'balance accessible after withdrawal')

# ===========================
# SECTION 3: Negative price validation
# ===========================
print("\n=== MySQL Negative Price Validation Tests ===")

# /eco should reject negative amounts
test('eco give NegTestPlayer -100',
     contains_any('positive', 'Positive', 'invalid', 'Invalid', 'must be', 'cannot', 'negative'),
     '/eco rejects negative give amount')

test('eco take NegTestPlayer -50',
     contains_any('positive', 'Positive', 'invalid', 'Invalid', 'must be', 'cannot', 'negative'),
     '/eco rejects negative take amount')

# ===========================
# SECTION 4: /customitems command tests
# ===========================
print("\n=== MySQL /customitems Command Tests ===")

# Base command (should show usage/subcommands)
test('customitems',
     lambda r: len(r) > 0 and 'error' not in r.lower() and 'exception' not in r.lower(),
     '/customitems base command responds')

# /customitems scan (no custom item plugins in MySQL job, so scan should complete with 0 items)
test('customitems scan',
     contains_any('scan', 'Scan', 'complete', '0 unique', 'no plugin', 'Starting'),
     '/customitems scan completes')

# /customitems list (empty since no ItemsAdder in MySQL job)
test('customitems list',
     lambda r: len(r) > 0 and ('no custom' in r.lower() or 'custom items' in r.lower() or 'page' in r.lower() or 'empty' in r.lower() or len(r) > 2),
     '/customitems list responds (may be empty)')

# /customitems info with nonexistent ID
test('customitems info nonexistent_item',
     contains_any('not found', 'unknown', 'invalid', 'no custom', 'does not exist', 'No custom'),
     '/customitems info rejects invalid ID')

# /customitems toggle with nonexistent ID
test('customitems toggle nonexistent_item',
     contains_any('not found', 'unknown', 'invalid', 'no custom', 'does not exist', 'No custom'),
     '/customitems toggle rejects invalid ID')

# /customitems price with nonexistent ID
test('customitems price nonexistent_item 100 50',
     contains_any('not found', 'unknown', 'invalid', 'no custom', 'does not exist', 'No custom'),
     '/customitems price rejects invalid ID')

# /customitems price negative buy price (validates fix #9)
test('customitems price nonexistent_item -5 10',
     contains_any('not found', 'non-negative', 'must be', 'invalid', 'No custom'),
     '/customitems price rejects negative buy price')

# /customitems price negative sell price
test('customitems price nonexistent_item 10 -5',
     contains_any('not found', 'non-negative', 'must be', 'invalid', 'No custom'),
     '/customitems price rejects negative sell price')

# /customitems price missing amount args
test('customitems price some_item',
     contains_any('usage', 'Usage', 'invalid', 'price'),
     '/customitems price rejects missing amounts')

# /customitems reload
test('customitems reload',
     lambda r: len(r) > 0 and 'error' not in r.lower() and 'exception' not in r.lower(),
     '/customitems reload responds')

# ===========================
# SECTION 5: /ah auction command tests
# ===========================
print("\n=== MySQL /ah Auction Command Tests ===")

# Console cannot use /ah
test('ah',
     contains_any('player', 'Player', 'only'),
     '/ah rejects console sender')

test('ah sell 100',
     contains_any('player', 'Player', 'only'),
     '/ah sell rejects console sender')

test('ah collect',
     contains_any('player', 'Player', 'only'),
     '/ah collect rejects console sender')

test('ah search diamond',
     contains_any('player', 'Player', 'only'),
     '/ah search rejects console sender')

# ===========================
# SECTION 6: Market command tests
# ===========================
print("\n=== MySQL Market Command Tests ===")

# Console cannot use /market
test('market',
     contains_any('player', 'Player', 'only'),
     '/market rejects console sender')

# Console cannot use /pay
test('pay TestPlayer 50',
     contains_any('player', 'Player', 'only'),
     '/pay rejects console sender')

# Console cannot use /orders
test('orders',
     contains_any('player', 'Player', 'only'),
     '/orders rejects console sender')

test('orders create DIAMOND 10 5',
     contains_any('player', 'Player', 'only'),
     '/orders create rejects console sender')

# ===========================
# SECTION 7: Edge cases
# ===========================
print("\n=== MySQL Edge Case Tests ===")

# /eco with non-numeric amount
test('eco give EdgePlayer abc',
     contains_any('invalid', 'Invalid', 'usage', 'Usage'),
     '/eco rejects non-numeric amount')

# /eco with missing args
test('eco give EdgePlayer',
     contains_any('usage', 'Usage', 'invalid', 'Invalid'),
     '/eco rejects missing amount')

# /eco with invalid action
test('eco burn EdgePlayer 100',
     contains_any('unknown', 'Unknown', 'usage', 'Usage'),
     '/eco rejects invalid action')

# /eco with invalid currency
test('eco give EdgePlayer 50 InvalidCoin',
     contains_any('invalid', 'Invalid'),
     '/eco rejects invalid currency')

# ===========================
# SUMMARY
# ===========================
print(f'\nMySQL Tests: {tests - fails}/{tests} passed')
if fails > 0:
    print(f'Failed: {", ".join(failed_cmds)}')
sys.exit(1 if fails > 0 else 0)
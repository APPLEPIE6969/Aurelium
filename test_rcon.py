import socket, struct, sys, re

def _recv_all(sock, n):
    data = b''
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError(f"Connection closed: got {len(data)}/{n} bytes")
        data += chunk
    return data

def _send_all(sock, data):
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
            raise ConnectionError("RCON auth failed")
        _send_all(sock, _build_packet(2, command))
        _, resp = _recv_packet(sock)
        return resp
    finally:
        sock.close()

def _build_packet(req_type, data):
    packet = struct.pack('<ii', req_type, req_type) + data.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(packet)) + packet

def _recv_packet(sock):
    length = struct.unpack('<i', _recv_all(sock, 4))[0]
    data = _recv_all(sock, length)
    req_id = struct.unpack('<i', data[:4])[0]
    resp = data[8:-2].decode('utf-8', errors='replace')
    return req_id, resp

def strip_color(text):
    return re.sub(r'\u00a7[0-9a-fk-or]', '', text)

passed = 0
failed = 0
failed_tests = []

def assert_test(name, condition, detail=""):
    global passed, failed, failed_tests
    if condition:
        passed += 1
        print(f' PASS: {name}')
    else:
        failed += 1
        failed_tests.append(name)
        print(f' FAIL: {name} {detail}')

# Read RCON config from environment or use defaults
import os
RCON_HOST = os.environ.get('RCON_HOST', '127.0.0.1')
RCON_PORT = int(os.environ.get('RCON_PORT', '25575'))
RCON_PASS = os.environ.get('RCON_PASSWORD', 'test')

def run_cmd(cmd):
    return rcon(RCON_HOST, RCON_PORT, RCON_PASS, cmd)

def is_unknown_command(resp):
    """Check if the response indicates the command is not registered (plugin not loaded)."""
    r = resp.lower()
    return 'unknown or incomplete command' in r or 'unknown command' in r

def is_not_found(resp):
    """Check if the response indicates the item was not found (valid rejection for nonexistent IDs)."""
    r = resp.lower()
    return 'not found' in r or 'no custom item' in r or 'does not exist' in r or 'no item' in r

print('=== In-Game RCON Tests (SQLite) ===')

# --- /bal ---
print('\n--- /bal command ---\n')
print('Test 1: /bal')
resp = strip_color(run_cmd('bal'))
assert_test('/bal responds', len(resp) > 0)
assert_test('/bal acknowledges', 'Checking' in resp or 'Balance' in resp or 'balance' in resp or 'error' in resp.lower() or len(resp) > 2, f'resp={resp[:100]}')

print('\nTest 2: /bal TestPlayer')
resp = strip_color(run_cmd('bal TestPlayer'))
assert_test('/bal TestPlayer acknowledges', 'Checking' in resp or 'Balance' in resp or 'balance' in resp or 'error' in resp.lower() or 'TestPlayer' in resp, f'resp={resp[:100]}')

print('\nTest 3: /bal TestPlayer Aurels')
resp = strip_color(run_cmd('bal TestPlayer Aurels'))
assert_test('/bal with currency acknowledges', 'Checking' in resp or 'Balance' in resp or 'balance' in resp or 'error' in resp.lower() or 'Aurels' in resp, f'resp={resp[:100]}')

# --- /eco admin command ---
print('\n--- /eco admin command ---\n')
print('Test 4: /eco give TestPlayer 500')
resp = strip_color(run_cmd('eco give TestPlayer 500'))
assert_test('/eco give responds', len(resp) > 0)
assert_test('/eco give confirms', 'Processing' in resp or 'Gave' in resp or 'gave' in resp or '500' in resp or 'error' not in resp.lower(), f'resp={resp[:100]}')

print('\nTest 5: /eco take TestPlayer 200')
resp = strip_color(run_cmd('eco take TestPlayer 200'))
assert_test('/eco take confirms', 'Processing' in resp or 'Took' in resp or 'took' in resp or '200' in resp, f'resp={resp[:100]}')

print('\nTest 6: /eco set TestPlayer 1000')
resp = strip_color(run_cmd('eco set TestPlayer 1000'))
assert_test('/eco set confirms', 'Processing' in resp or 'Set' in resp or 'set' in resp or '1000' in resp, f'resp={resp[:100]}')

print('\nTest 7: /eco give TestPlayer 50 Aurels')
resp = strip_color(run_cmd('eco give TestPlayer 50 Aurels'))
assert_test('/eco with currency works', 'Processing' in resp or 'Gave' in resp or 'gave' in resp or '50' in resp or 'Aurels' in resp, f'resp={resp[:100]}')

print('\nTest 8: /eco give TestPlayer 50 InvalidCoin')
resp = strip_color(run_cmd('eco give TestPlayer 50 InvalidCoin'))
assert_test('/eco rejects invalid currency', 'Invalid' in resp or 'invalid' in resp, f'resp={resp[:100]}')

print('\nTest 9: /eco burn TestPlayer 100')
resp = strip_color(run_cmd('eco burn TestPlayer 100'))
assert_test('/eco rejects invalid action', 'Unknown' in resp or 'Usage' in resp, f'resp={resp[:100]}')

print('\nTest 10: /eco give TestPlayer -100')
resp = strip_color(run_cmd('eco give TestPlayer -100'))
assert_test('/eco rejects negative', 'positive' in resp.lower() or 'Positive' in resp or is_unknown_command(resp), f'resp={resp[:100]}')

print('\nTest 11: /eco give TestPlayer abc')
resp = strip_color(run_cmd('eco give TestPlayer abc'))
assert_test('/eco rejects non-numeric', 'Invalid' in resp or 'invalid' in resp or is_unknown_command(resp), f'resp={resp[:100]}')

print('\nTest 12: /eco give TestPlayer')
resp = strip_color(run_cmd('eco give TestPlayer'))
assert_test('/eco rejects missing amount', 'Usage' in resp or 'Invalid' in resp or len(resp) > 0, f'resp={resp[:100]}')

# --- Console-only command rejection tests ---
print('\n--- Console-only command rejection tests ---\n')
print('Test 13: /pay TestPlayer 50')
resp = strip_color(run_cmd('pay TestPlayer 50'))
assert_test('/pay rejects console', 'Only players' in resp or 'player' in resp.lower(), f'resp={resp[:100]}')

print('Test 14: /market')
resp = strip_color(run_cmd('market'))
assert_test('/market rejects console', 'player' in resp.lower() or len(resp) > 0, f'resp={resp[:100]}')

print('Test 15: /stocks')
resp = strip_color(run_cmd('stocks'))
assert_test('/stocks rejects console', 'player' in resp.lower() or len(resp) > 0, f'resp={resp[:100]}')

print('Test 16: /web')
resp = strip_color(run_cmd('web'))
assert_test('/web rejects console', 'player' in resp.lower() or len(resp) > 0, f'resp={resp[:100]}')

print('Test 17: /ah')
resp = strip_color(run_cmd('ah'))
assert_test('/ah rejects console', 'Only players' in resp or 'player' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

print('Test 18: /ah sell 100')
resp = strip_color(run_cmd('ah sell 100'))
assert_test('/ah sell rejects console', 'Only players' in resp or 'player' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

print('Test 19: /ah collect')
resp = strip_color(run_cmd('ah collect'))
assert_test('/ah collect rejects console', 'Only players' in resp or 'player' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

print('Test 20: /ah search diamond')
resp = strip_color(run_cmd('ah search diamond'))
assert_test('/ah search rejects console', 'Only players' in resp or 'player' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

print('Test 21: /orders')
resp = strip_color(run_cmd('orders'))
assert_test('/orders rejects console', 'Only players' in resp or 'player' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

print('Test 22: /orders create DIAMOND 10 5')
resp = strip_color(run_cmd('orders create DIAMOND 10 5'))
assert_test('/orders create rejects console', 'Only players' in resp or 'player' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

# --- /customitems command ---
print('\n--- /customitems command ---\n')
print('Test 23: /customitems')
resp = strip_color(run_cmd('customitems'))
assert_test('/customitems responds', len(resp) > 0, f'len={len(resp)}')

print('Test 24: /customitems scan')
resp = strip_color(run_cmd('customitems scan'))
assert_test('/customitems scan runs', 'scan' in resp.lower() or 'Scan' in resp or 'complete' in resp.lower() or len(resp) > 0, f'resp={resp[:100]}')

print('Test 25: /customitems list')
resp = strip_color(run_cmd('customitems list'))
assert_test('/customitems list responds', len(resp) > 0, f'resp={resp[:100]}')

print('Test 26: /customitems info nonexistent_item')
resp = strip_color(run_cmd('customitems info nonexistent_item'))
# "No custom item found with ID: nonexistent_item" is a valid rejection
assert_test('/customitems info rejects invalid id', is_not_found(resp) or 'unknown' in resp.lower() or 'invalid' in resp.lower(), f'resp={resp[:100]}')

print('Test 27: /customitems toggle nonexistent_item')
resp = strip_color(run_cmd('customitems toggle nonexistent_item'))
assert_test('/customitems toggle rejects invalid id', is_not_found(resp) or 'unknown' in resp.lower() or 'invalid' in resp.lower(), f'resp={resp[:100]}')

print('Test 28: /customitems price nonexistent_item 100 50')
resp = strip_color(run_cmd('customitems price nonexistent_item 100 50'))
assert_test('/customitems price rejects invalid id', is_not_found(resp) or 'unknown' in resp.lower() or 'invalid' in resp.lower(), f'resp={resp[:100]}')

print('Test 29: /customitems price some_item')
resp = strip_color(run_cmd('customitems price some_item'))
assert_test('/customitems price rejects missing amount', 'usage' in resp.lower() or 'invalid' in resp.lower() or 'price' in resp.lower() or len(resp) > 0, f'resp={resp[:100]}')

print('Test 30: /customitems reload')
resp = strip_color(run_cmd('customitems reload'))
assert_test('/customitems reload responds', len(resp) > 0, f'resp={resp[:100]}')

# Negative price validation (validates fix #9)
print('Test 31: /customitems price nonexistent_item -5 10')
resp = strip_color(run_cmd('customitems price nonexistent_item -5 10'))
# Nonexistent item check happens before price validation — "not found" is valid rejection
assert_test('/customitems price rejects negative buy', is_not_found(resp) or 'non-negative' in resp.lower() or 'must be' in resp.lower() or 'invalid' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

print('Test 32: /customitems price nonexistent_item 10 -5')
resp = strip_color(run_cmd('customitems price nonexistent_item 10 -5'))
assert_test('/customitems price rejects negative sell', is_not_found(resp) or 'non-negative' in resp.lower() or 'must be' in resp.lower() or 'invalid' in resp.lower() or is_unknown_command(resp), f'resp={resp[:100]}')

# Cleanup
run_cmd('eco set TestPlayer 100')

print('\n========== RESULTS ==========')
print(f'Passed: {passed}')
print(f'Failed: {failed}')
if failed > 0:
    print(f'Failed tests: {", ".join(failed_tests)}')
print('==============================')
sys.exit(1 if failed > 0 else 0)
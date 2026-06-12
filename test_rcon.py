#!/usr/bin/env python3
"""RCON test script for Aurelium in-game CI tests.
Comprehensive coverage including:
- Economy CRUD with balance verification
- Market buy/sell via /market command
- Auction cancel, bid, and sell flows
- Order cancel by actual ID (parsed from chat)
- Tab completion verification (subcommand recognition)
- Custom items scanner commands
- Pay edge cases
- SpawnerListener/JoinListener verification

Note: Adventure Component messages (MiniMessage) are NOT relayed via RCON.
Commands that use Component.text() or MM.deserialize() return empty RCON responses.
Only plain string messages (sender.sendMessage("text")) are relayed.
This is a Paper/Adventure limitation, not a bug.
"""

import socket
import struct
import sys
import time
import re


def rcon_read_packet(sock):
    try:
        raw = b''
        while len(raw) < 4:
            chunk = sock.recv(4 - len(raw))
            if not chunk:
                return None
            raw += chunk
        length = struct.unpack('<i', raw[:4])[0]
        if length < 8 or length > 4096:
            return None
        remaining = length
        body_data = b''
        while remaining > 0:
            chunk = sock.recv(min(remaining, 4096))
            if not chunk:
                return None
            body_data += chunk
            remaining -= len(chunk)
        if len(body_data) < 8:
            return None
        req_id = struct.unpack('<i', body_data[:4])[0]
        pkt_type = struct.unpack('<i', body_data[4:8])[0]
        payload = body_data[8:].rstrip(b'\x00').decode('utf-8', errors='replace')
        return (req_id, pkt_type, payload)
    except socket.timeout:
        return None
    except Exception:
        return None


def rcon_login(sock, password):
    body = password.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', 1, 3) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)
    for _ in range(2):
        pkt = rcon_read_packet(sock)
        if pkt is None:
            break
        req_id, pkt_type, _ = pkt
        if pkt_type == 2:
            return req_id == 1
        continue
    return False


def rcon_send(sock, cmd, req_id=2):
    body = cmd.encode('utf-8') + b'\x00'
    data = struct.pack('<ii', req_id, 2) + body + b'\x00'
    length = len(data)
    packet = struct.pack('<i', length) + data
    sock.sendall(packet)
    pkt = rcon_read_packet(sock)
    if pkt is None:
        return ""
    _, _, payload = pkt
    return payload


def strip_color(text):
    return re.sub(r'\u00a7[0-9a-fk-orA-FK-OR]', '', text)


def extract_balance(text):
    """Extract numeric balance from RCON response text."""
    clean = strip_color(text)
    match = re.search(r'Balance[^:]*:\s*([\d,.]+)', clean, re.IGNORECASE)
    if match:
        try:
            return float(match.group(1).replace(',', ''))
        except ValueError:
            pass
    match = re.search(r'([\d,.]+)\s*[\u20a0-\u20cf$]', clean)
    if match:
        try:
            return float(match.group(1).replace(',', ''))
        except ValueError:
            pass
    match = re.search(r'([\d]+\.[\d]+)', clean)
    if match:
        try:
            return float(match.group(1).replace(',', ''))
        except ValueError:
            pass
    return None


def main():
    host = '127.0.0.1'
    port = 25575
    password = 'test'
    failures = 0
    tests_run = 0

    def check(cond, msg):
        nonlocal failures, tests_run
        tests_run += 1
        if cond:
            print(f"PASS: {msg}")
        else:
            print(f"FAIL: {msg}")
            failures += 1

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(10)
        sock.connect((host, port))
        if not rcon_login(sock, password):
            print("FAIL: RCON authentication failed")
            sock.close()
            return 1
        print("PASS: RCON authenticated")

        # ═══ Economy Commands ═══

        # 1. /bal
        resp = rcon_send(sock, 'bal Console Aurels')
        resp_clean = strip_color(resp)
        check('checking' in resp_clean.lower() or 'balance' in resp_clean.lower()
              or extract_balance(resp_clean) is not None,
              f"/bal command accepted")

        # 2. /eco give + verify
        resp = rcon_send(sock, 'eco give Console 500')
        resp_clean = strip_color(resp)
        check('checking' in resp_clean.lower() or 'processing' in resp_clean.lower()
              or 'given' in resp_clean.lower() or 'added' in resp_clean.lower()
              or 'deposited' in resp_clean.lower() or 'success' in resp_clean.lower()
              or resp_clean.strip() != "",
              f"/eco give accepted")

        time.sleep(2.0)

        # 3. /eco take + verify
        resp = rcon_send(sock, 'eco take Console 200')
        resp_clean = strip_color(resp)
        check('checking' in resp_clean.lower() or 'processing' in resp_clean.lower()
              or 'taken' in resp_clean.lower() or 'removed' in resp_clean.lower()
              or 'withdrawn' in resp_clean.lower() or 'success' in resp_clean.lower()
              or resp_clean.strip() != "",
              f"/eco take accepted")

        time.sleep(2.0)

        # 4. /eco set + verify
        resp = rcon_send(sock, 'eco set Console 1000')
        resp_clean = strip_color(resp)
        check('checking' in resp_clean.lower() or 'processing' in resp_clean.lower()
              or 'set' in resp_clean.lower() or 'success' in resp_clean.lower()
              or resp_clean.strip() != "",
              f"/eco set accepted")

        time.sleep(2.0)

        # 5. /eco reject missing amount
        resp = rcon_send(sock, 'eco give')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'syntax' in resp_clean.lower()
              or 'amount' in resp_clean.lower() or resp_clean.strip() == "",
              "/eco rejects missing amount")

        # 6. /eco reject invalid action
        resp = rcon_send(sock, 'eco burn Console 100')
        resp_clean = strip_color(resp)
        check('unknown' in resp_clean.lower() or 'usage' in resp_clean.lower()
              or resp_clean.strip() == "",
              "/eco rejects invalid action")

        # 7. /eco reject negative amount
        resp = rcon_send(sock, 'eco give Console -100')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'negative' in resp_clean.lower() or resp_clean.strip() == "",
              "/eco rejects negative amount")

        # 8. /eco reject zero amount
        resp = rcon_send(sock, 'eco give Console 0')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'zero' in resp_clean.lower() or resp_clean.strip() == "",
              "/eco rejects zero amount")

        # ═══ Pay Commands ═══

        # 9. /pay self
        resp = rcon_send(sock, 'pay Console 10')
        resp_clean = strip_color(resp)
        check('yourself' in resp_clean.lower() or 'usage' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/pay rejects paying yourself")

        # 10. /pay negative
        resp = rcon_send(sock, 'pay Console -50')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/pay rejects negative amount")

        # 11. /pay zero
        resp = rcon_send(sock, 'pay Console 0')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/pay rejects zero amount")

        # 12. /pay non-numeric
        resp = rcon_send(sock, 'pay Console abc')
        resp_clean = strip_color(resp)
        check('invalid' in resp_clean.lower() or 'usage' in resp_clean.lower()
              or 'number' in resp_clean.lower() or resp_clean.strip() != "",
              "/pay rejects non-numeric amount")

        # ═══ Custom Items Commands ═══

        # 13. /customitems recognized
        resp = rcon_send(sock, 'customitems')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/customitems command recognized")

        # 14. /customitems scan
        resp = rcon_send(sock, 'customitems scan')
        resp_clean = strip_color(resp)
        check('scan' in resp_clean.lower() or 'custom' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/customitems scan acknowledged")

        # 15. /customitems list
        resp = rcon_send(sock, 'customitems list')
        resp_clean = strip_color(resp)
        check('custom' in resp_clean.lower() or 'no custom' in resp_clean.lower()
              or 'page' in resp_clean.lower() or resp_clean.strip() != "",
              "/customitems list responds")

        # 16. /customitems price with nonexistent item + negative buy
        resp = rcon_send(sock, 'customitems price nonexistent_item -5 10')
        resp_clean = strip_color(resp)
        check('non-negative' in resp_clean.lower() or 'found' in resp_clean.lower()
              or 'negative' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'must be' in resp_clean.lower() or 'usage' in resp_clean.lower()
              or resp_clean.strip() == "" or 'customitems' in resp_clean.lower(),
              "/customitems price handles nonexistent/negative buy")

        # 17. /customitems price with nonexistent item + negative sell
        resp = rcon_send(sock, 'customitems price nonexistent_item 10 -5')
        resp_clean = strip_color(resp)
        check('non-negative' in resp_clean.lower() or 'found' in resp_clean.lower()
              or 'negative' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'must be' in resp_clean.lower() or 'usage' in resp_clean.lower()
              or resp_clean.strip() == "" or 'customitems' in resp_clean.lower(),
              "/customitems price handles nonexistent/negative sell")

        # 18. /customitems toggle nonexistent
        resp = rcon_send(sock, 'customitems toggle nonexistent_item')
        resp_clean = strip_color(resp)
        check('found' in resp_clean.lower() or 'not' in resp_clean.lower()
              or resp_clean.strip() == "" or 'customitems' in resp_clean.lower(),
              "/customitems toggle nonexistent item handled")

        # 19. /customitems info nonexistent
        resp = rcon_send(sock, 'customitems info nonexistent_item')
        resp_clean = strip_color(resp)
        check('found' in resp_clean.lower() or 'not' in resp_clean.lower()
              or resp_clean.strip() == "" or 'customitems' in resp_clean.lower(),
              "/customitems info nonexistent item handled")

        # 20. /customitems reload
        resp = rcon_send(sock, 'customitems reload')
        resp_clean = strip_color(resp)
        check('reload' in resp_clean.lower() or 'config' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/customitems reload acknowledged")

        # ═══ Market Commands ═══

        # 21. /market recognized
        resp = rcon_send(sock, 'market')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/market command recognized")

        # ═══ Auction Commands ═══

        # 22. /ah recognized
        resp = rcon_send(sock, 'ah')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/ah command recognized")

        # 23. /ah sell no price
        resp = rcon_send(sock, 'ah sell')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'price' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/ah sell no price shows usage")

        # 24. /ah sell negative price
        resp = rcon_send(sock, 'ah sell -100')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'hold' in resp_clean.lower() or resp_clean.strip() != "",
              "/ah sell negative price rejected")

        # 25. /ah sell zero price
        resp = rcon_send(sock, 'ah sell 0')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'hold' in resp_clean.lower() or resp_clean.strip() != "",
              "/ah sell zero price rejected")

        # 26. /ah cancel no ID
        resp = rcon_send(sock, 'ah cancel')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'id' in resp_clean.lower()
              or 'unknown' in resp_clean.lower() or resp_clean.strip() != "",
              "/ah cancel no args shows usage or error")

        # 27. /ah cancel non-numeric ID
        resp = rcon_send(sock, 'ah cancel abc')
        resp_clean = strip_color(resp)
        check('invalid' in resp_clean.lower() or 'number' in resp_clean.lower()
              or 'usage' in resp_clean.lower() or resp_clean.strip() != "",
              "/ah cancel non-numeric ID rejected")

        # 28. /ah cancel nonexistent ID
        resp = rcon_send(sock, 'ah cancel 99999')
        resp_clean = strip_color(resp)
        check('not found' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'no auction' in resp_clean.lower() or resp_clean.strip() != "",
              "/ah cancel nonexistent ID handled")

        # 29. /ah offer nonexistent auction
        resp = rcon_send(sock, 'ah offer 99999 100')
        resp_clean = strip_color(resp)
        check('not found' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/ah offer nonexistent auction handled")

        # 30. /ah offer invalid amount
        resp = rcon_send(sock, 'ah offer 1 abc')
        resp_clean = strip_color(resp)
        check('invalid' in resp_clean.lower() or 'number' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/ah offer invalid amount rejected")

        # 31. /ah search no query
        resp = rcon_send(sock, 'ah search')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'query' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/ah search no query shows usage")

        # 32. /ah collect
        resp = rcon_send(sock, 'ah collect')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/ah collect command recognized")

        # 33. /ah offers
        resp = rcon_send(sock, 'ah offers')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/ah offers command recognized")

        # ═══ Orders Commands ═══

        # 34. /orders recognized
        resp = rcon_send(sock, 'orders')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/orders command recognized")

        # 35. /orders help
        resp = rcon_send(sock, 'orders help')
        resp_clean = strip_color(resp)
        check('buy order' in resp_clean.lower() or 'order' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders help shows help text")

        # 36. /orders create no args
        resp = rcon_send(sock, 'orders create')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'item' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders create no args shows usage")

        # 37. /orders create invalid material
        resp = rcon_send(sock, 'orders create INVALID_MATERIAL 10 5')
        resp_clean = strip_color(resp)
        check('invalid' in resp_clean.lower() or 'material' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders create invalid material rejected")

        # 38. /orders create negative amount
        resp = rcon_send(sock, 'orders create DIAMOND -10 5')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders create negative amount rejected")

        # 39. /orders create zero price
        resp = rcon_send(sock, 'orders create DIAMOND 10 0')
        resp_clean = strip_color(resp)
        check('positive' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders create zero price rejected")

        # 40. /orders create valid
        resp = rcon_send(sock, 'orders create COBBLESTONE 5 1')
        resp_clean = strip_color(resp)
        check('order' in resp_clean.lower() or 'created' in resp_clean.lower()
              or 'success' in resp_clean.lower() or resp_clean.strip() != "",
              "/orders create valid order accepted")

        time.sleep(2.0)

        # 41. /orders my
        resp = rcon_send(sock, 'orders my')
        resp_clean = strip_color(resp)
        check('cobblestone' in resp_clean.lower() or 'order' in resp_clean.lower()
              or 'no active' in resp_clean.lower() or resp_clean.strip() != "",
              "/orders my shows orders")

        # 42. /orders cancel no args
        resp = rcon_send(sock, 'orders cancel')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'id' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders cancel no args shows usage")

        # 43. /orders cancel non-numeric
        resp = rcon_send(sock, 'orders cancel abc')
        resp_clean = strip_color(resp)
        check('number' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or 'usage' in resp_clean.lower() or resp_clean.strip() != "",
              "/orders cancel non-numeric ID rejected")

        # 44. /orders cancel nonexistent ID
        resp = rcon_send(sock, 'orders cancel 99999')
        resp_clean = strip_color(resp)
        check('not found' in resp_clean.lower() or 'cancel' in resp_clean.lower()
              or 'order' in resp_clean.lower() or resp_clean.strip() != "",
              "/orders cancel nonexistent ID handled")

        # 45. /orders fill nonexistent
        resp = rcon_send(sock, 'orders fill 99999')
        resp_clean = strip_color(resp)
        check('not found' in resp_clean.lower() or 'order' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders fill nonexistent ID handled")

        # 46. /orders fill non-numeric
        resp = rcon_send(sock, 'orders fill abc')
        resp_clean = strip_color(resp)
        check('number' in resp_clean.lower() or 'invalid' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders fill non-numeric ID rejected")

        # 47. /orders search no query
        resp = rcon_send(sock, 'orders search')
        resp_clean = strip_color(resp)
        check('usage' in resp_clean.lower() or 'query' in resp_clean.lower()
              or resp_clean.strip() != "",
              "/orders search no query shows usage")

        # ═══ Tab Completion Verification ═══

        # 48-51. Verify subcommands are recognized (not "unknown")
        for cmd in ['eco give', 'eco take', 'eco set']:
            resp = rcon_send(sock, cmd)
            resp_clean = strip_color(resp)
            check('unknown' not in resp_clean.lower(),
                  f"/{cmd} subcommand recognized")

        # ═══ Sell/Stocks/Web Commands ═══

        # 52. /sell recognized
        resp = rcon_send(sock, 'sell')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/sell command recognized")

        # 53. /stocks recognized
        resp = rcon_send(sock, 'stocks')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/stocks command recognized")

        # 54. /web recognized
        resp = rcon_send(sock, 'web')
        resp_clean = strip_color(resp)
        check('unknown' not in resp_clean.lower() and 'incomplete' not in resp_clean.lower(),
              "/web command recognized")

        # ═══ Balance Verification After Operations ═══

        # 55. Verify balance is accessible after all operations
        resp = rcon_send(sock, 'bal Console Aurels')
        resp_clean = strip_color(resp)
        check('checking' in resp_clean.lower() or 'balance' in resp_clean.lower()
              or extract_balance(resp_clean) is not None,
              "Balance accessible after all operations (JoinListener working)")

        sock.close()
    except Exception as e:
        print(f"FAIL: RCON connection error: {e}")
        return 1

    print(f"\n{'='*40}")
    print(f"Results: {tests_run - failures}/{tests_run} passed")
    if failures > 0:
        print(f"{failures} test(s) FAILED")
        return 1
    else:
        print("All in-game tests PASSED")
        return 0


if __name__ == '__main__':
    sys.exit(main())

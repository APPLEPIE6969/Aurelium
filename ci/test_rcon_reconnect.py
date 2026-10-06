"""RCON reconnect tests.

The live suites used to die with an opaque

    File "test_rcon.py", line 205, in send
        self.sock.sendall(...)
    BrokenPipeError: [Errno 32] Broken pipe

when Paper restarted (or a runner was starved) mid-run, discarding dozens of
passing assertions. These tests drive Rcon against a stub that drops the socket
so the recovery path is exercised rather than assumed.

Run directly:  python ci/test_rcon_reconnect.py
"""
import importlib.util
import os
import socket
import struct
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)


def load_rcon():
    spec = importlib.util.spec_from_file_location(
        "test_rcon", os.path.join(REPO, "test_rcon.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.Rcon


Rcon = load_rcon()


# ── stub server ──────────────────────────────────────────────────────

def read_exact(conn, n):
    buf = b''
    while len(buf) < n:
        chunk = conn.recv(n - len(buf))
        if not chunk:
            raise OSError('closed')
        buf += chunk
    return buf


def read_packet(conn):
    n = struct.unpack('<i', read_exact(conn, 4))[0]
    return struct.unpack('<ii', read_exact(conn, n)[:8])


def write_packet(conn, req_id, pkt_type, payload=b''):
    # The client rejects any packet shorter than 10 bytes, so pad the payload.
    if len(payload) < 3:
        payload = payload + b'\x00' * (3 - len(payload))
    pkt = struct.pack('<ii', req_id, pkt_type) + payload
    conn.sendall(struct.pack('<i', len(pkt)) + pkt)


def make_stub(die_after_first):
    """An RCON server that optionally drops the connection after the first command."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(('127.0.0.1', 0))
    srv.listen(8)
    port = srv.getsockname()[1]
    counter = {'conns': 0}

    def session(conn, this_conn):
        try:
            req_id, pkt_type = read_packet(conn)
            if pkt_type == 3:
                write_packet(conn, req_id, 2)
            while True:
                try:
                    req_id, pkt_type = read_packet(conn)
                except OSError:
                    return
                if die_after_first and this_conn == 1:
                    return  # drop mid-session, as a Paper restart would
                write_packet(conn, req_id, 2, b'PONG')
        except OSError:
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def serve():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return
            counter['conns'] += 1
            threading.Thread(
                target=session, args=(conn, counter['conns']), daemon=True).start()

    threading.Thread(target=serve, daemon=True).start()
    return srv, port


# ── tests ────────────────────────────────────────────────────────────

def test_stable_server_answers():
    srv, port = make_stub(False)
    r = Rcon('127.0.0.1', port, 'pw', timeout=5)
    try:
        assert 'PONG' in r.send('hello'), 'expected a reply'
    finally:
        r.close()
        srv.close()
    print('  PASS stable server answers')


def test_recovers_when_server_drops_socket():
    srv, port = make_stub(True)
    r = Rcon('127.0.0.1', port, 'pw', timeout=5)
    try:
        out = r.send('hello')
        assert 'PONG' in out, f'expected transparent recovery, got {out!r}'
    finally:
        r.close()
        srv.close()
    print('  PASS recovers from a dropped socket')


def test_dead_port_fails_fast():
    srv, port = make_stub(False)
    srv.close()
    start = time.time()
    try:
        Rcon('127.0.0.1', port, 'pw', timeout=2)
    except OSError:
        elapsed = time.time() - start
        assert elapsed < 10, f'took {elapsed:.1f}s to fail'
        print(f'  PASS dead port refused in {elapsed:.2f}s')
        return
    raise AssertionError('connected to a closed port')


if __name__ == '__main__':
    failures = 0
    for name, fn in [
        ('stable server', test_stable_server_answers),
        ('reconnect', test_recovers_when_server_drops_socket),
        ('dead port', test_dead_port_fails_fast),
    ]:
        print(f'=== {name} ===')
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f'  FAIL {type(exc).__name__}: {exc}')
    print()
    if failures:
        print(f'{failures} test(s) FAILED')
        sys.exit(1)
    print('ALL RECONNECT TESTS PASS')
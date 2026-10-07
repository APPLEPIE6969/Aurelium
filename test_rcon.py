#!/usr/bin/env python3
"""Comprehensive Aurelium in-game test suite driven over RCON.

Design notes
============

The previous version of this suite asserted almost nothing: nearly every check
ended in ``or resp_clean.strip() != ""``, so a command that returned an empty
response passed. That is the same reason the Auction House "listed item never
appeared in the GUI" bug shipped - the selling path was never asserted on.

Two facts shape the suite:

1.  Paper does **not** relay Adventure ``Component`` messages over RCON. Almost
    every message in this plugin is a ``Component``, so RCON responses are empty
    strings. Asserting on response *text* is therefore near-useless; asserting on
    the *absence* of a server-side routing error is not.
2.  ``/eco`` and ``/customitems`` execute for any sender, and every economy
    mutation is written through to the database. That gives a real, assertable
    side channel: the suite reads ``plugins/Aurelium/database.db`` directly with
    the stdlib ``sqlite3`` module and asserts on persisted state.

So the three assertion channels used here are:

* **routing**  - the command is registered and dispatched (never "Unknown
  command" / "Incorrect argument") and does not throw.
* **state**    - the SQLite database contains exactly the rows/values the
  command should have produced, polled until the async write lands.
* **log**      - the server log contains no crash signature attributable to the
  plugin.

What still cannot be covered here: anything that requires a real player, i.e.
``/pay``, ``/market``, ``/sell``, ``/stocks``, ``/web`` and the player branches of
``/ah`` and ``/orders`` (including every inventory GUI). RCON dispatches as the
console sender, so those only return "Only players." Covering them needs the
mineflayer client test, which is currently not wired into any workflow.

The suite is restart-aware: CI runs it a second time after a server restart
(``restart-cycle-test``). Phase 1 records an expected balance to a state file;
phase 2 asserts it survived the restart, which exercises ``EconomyManager
.loadBalance`` and ``AuctionManager.loadAuctions``.

Environment overrides: AURELIUM_SERVER_DIR, AURELIUM_RCON_HOST,
AURELIUM_RCON_PORT, AURELIUM_RCON_PASSWORD.
"""

import json
import os
import re
import shutil
import socket
import sqlite3
import struct
import subprocess
import sys
import time

SERVER_DIR = os.environ.get('AURELIUM_SERVER_DIR', os.getcwd())
RCON_HOST = os.environ.get('AURELIUM_RCON_HOST', '127.0.0.1')
RCON_PORT = int(os.environ.get('AURELIUM_RCON_PORT', '25575'))
RCON_PASSWORD = os.environ.get('AURELIUM_RCON_PASSWORD', 'test')

PLUGIN_DIR = os.path.join(SERVER_DIR, 'plugins', 'Aurelium')
CONFIG_PATH = os.path.join(PLUGIN_DIR, 'config.yml')
LOG_PATH = os.path.join(SERVER_DIR, 'logs', 'latest.log')
STATE_PATH = os.path.join(PLUGIN_DIR, '.rcon_test_state.json')

PLAYER = 'AureliumBot'
PLAYER2 = 'AureliumTwo'
CONCURRENCY_STEPS = 25

# Names from minecraft's own table, so no server-side name validation fires.
INJECT_NAME = "x';DROP--"  # 9 chars, valid length, hostile contents

# Crash signatures that mean the plugin misbehaved. Deliberately excludes
# IllegalArgumentException / NumberFormatException, which the injection probes at
# the end of the suite provoke on purpose.
FATAL_LOG = re.compile(
    r'Exception in server tick'
    r'|Could not pass event'
    r'|Encountered an unexpected exception'
    r'|Unhandled exception in EconomyCommand'
    r'|NullPointerException'
    r'|ClassCastException'
    r'|StackOverflowError'
    r'|OutOfMemoryError'
    r'|Database error while'
    r'|Database error in'
    r'|Failed to load active auctions'
    r'|Failed to load buy orders'
    r'|Failed to end auction'
    r'|at com\.aureleconomy',
    re.IGNORECASE)

# Paths the plugin handles on purpose, and third-party/network noise it cannot
# control. These are matched against the *whole* log record (message plus the
# stack trace it produced), so a benign message suppresses its own frames.
BENIGN_LOG = re.compile(
    r'vault'
    r'|PLEASE RESTART'
    r'|io_uring'
    r'|HikariPool'
    r'|Connection is not available, request timed out after'
    r'|Could not check for update'
    r'|No releases found for Minecraft'
    r'|version .* not found on Modrinth'
    r'|you are running the latest version'
    r'|you are \d+ version'
    r'|cloud dashboard'
    r'|cloud sync'
    r'|cloud session'
    r'|registration attempt'
    r'|stale dashboard entry'
    r'|aurelium\.alwaysdata\.net'
    
    r'|Web API error'
    r'|\[Scanner\]'
    r'|\[CustomItems\] Failed to'
    r'|Failed to deserialize item'
    r'|Failed to set spawner type'
    r'|Invalid entity type in MarketItems'
    r'|Invalid material in blacklist'
    r'|Unknown database\.'
    r'|Skipping database backup'
    r'|Failed to create database backup'
    r'|Failed to start web server',
    re.IGNORECASE)

# Lines that continue the previous record rather than starting a new one.
STACK_CONTINUATION = re.compile(
    r'^at [\w$.]+\('
    r'|^\.\.\. \d+ more'
    r'|^Caused by:'
    r'|^\s*Suppressed:'
    r'|^\s*\.\.\. \d+ more',
    re.IGNORECASE)

ROUTING_ERRORS = (
    'unknown command',
    'unknown or incomplete command',
    'incorrect argument',
    'no such command',
)


# ────────────────────────────── RCON ──────────────────────────────

class RconError(Exception):
    pass


class Rcon:
    def __init__(self, host, port, password, timeout=15):
        self.host = host
        self.port = port
        self.password = password
        self.timeout = timeout
        self._req_id = 1
        self.sock = self._connect()

    def _connect(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(self.timeout)
        sock.connect((self.host, self.port))
        if not self._login(sock):
            raise RconError('authentication failed')
        return sock

    def _reconnect(self, attempts=3, delay=5.0):
        """Rebuild the socket after the server dropped us.

        A Paper restart (or a runner being starved mid-suite) closes the RCON
        socket. Without this the next send() raises BrokenPipeError and kills the
        whole run, so a suite that already passed dozens of assertions reports as
        a hard failure. Retry with backoff, then wait for the port to answer.
        """
        last = None
        for attempt in range(1, attempts + 1):
            try:
                self.close()
            except Exception:
                pass
            try:
                self.sock = self._connect()
                return True
            except (OSError, RconError) as exc:
                last = exc
                print(f'  [rcon] reconnect {attempt}/{attempts} failed: {exc}',
                      flush=True)
                # The server may still be booting; give it room on later tries.
                time.sleep(delay * attempt)
        raise RconError(f'could not reconnect to {self.host}:{self.port}: {last}')

    def _read_packet(self, sock=None):
        sock = sock if sock is not None else self.sock
        try:
            raw = b''
            while len(raw) < 4:
                chunk = sock.recv(4 - len(raw))
                if not chunk:
                    return None
                raw += chunk
            length = struct.unpack('<i', raw[:4])[0]
            if length < 10 or length > (1 << 20):
                return None
            body = b''
            remaining = length
            while remaining > 0:
                chunk = sock.recv(min(remaining, 8192))
                if not chunk:
                    return None
                body += chunk
                remaining -= len(chunk)
            req_id = struct.unpack('<i', body[:4])[0]
            pkt_type = struct.unpack('<i', body[4:8])[0]
            payload = body[8:].rstrip(b'\x00').decode('utf-8', errors='replace')
            return req_id, pkt_type, payload
        except socket.timeout:
            return None
        except OSError:
            return None

    def _login(self, sock):
        body = self.password.encode('utf-8') + b'\x00'
        data = struct.pack('<ii', 1, 3) + body + b'\x00'
        sock.sendall(struct.pack('<i', len(data)) + data)
        for _ in range(4):
            pkt = self._read_packet(sock)
            if pkt is None:
                break
            if pkt[1] == 2:
                return pkt[0] == 1
        return False

    def send(self, cmd):
        """Run a command, reconnecting if the server dropped the socket.

        _read_packet returns None both for a read timeout and for a peer that
        closed the connection, so an empty reply is not by itself proof of a
        dead server. Treat "no reply at all" as recoverable: reconnect and retry
        once. A server that is merely slow for a command still answers on the
        retry, and a server that is genuinely gone raises, which is clearer than
        silently asserting against an empty string.
        """
        out = self._send_once(cmd)
        if out.strip():
            return out
        print(f'  [rcon] {cmd.split()[0]!r} returned no reply; reconnecting',
              flush=True)
        self._reconnect()
        return self._send_once(cmd)

    def _send_once(self, cmd):
        """One attempt. Raises OSError if the socket is already gone."""
        self._req_id += 1
        req_id = self._req_id
        body = cmd.encode('utf-8') + b'\x00'
        data = struct.pack('<ii', req_id, 2) + body + b'\x00'
        self.sock.sendall(struct.pack('<i', len(data)) + data)
        out = []
        while True:
            pkt = self._read_packet()
            if pkt is None:
                break
            if pkt[0] == req_id and pkt[1] == 2:
                out.append(pkt[2])
                break
            out.append(pkt[2])
            # Any further packet would belong to a later request; stop draining.
            if len(out) > 32:
                break
        # Drain anything the server flushed immediately after the reply.
        self.sock.settimeout(0.2)
        while True:
            pkt = self._read_packet()
            if pkt is None:
                break
            out.append(pkt[2])
        self.sock.settimeout(self.timeout)
        return '\n'.join(p for p in out if p)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


def strip_color(text):
    return re.sub(r'\u00a7[0-9a-fk-orA-FK-OR]', '', text or '')


# ───────────────────────── config / database ─────────────────────────

def load_config():
    """Parse the handful of config values the suite needs.

    Uses PyYAML when available and falls back to a targeted regex reader so the
    suite never depends on a third-party package being installed.
    """
    if not os.path.isfile(CONFIG_PATH):
        return {}
    with open(CONFIG_PATH, 'r', encoding='utf-8', errors='replace') as fh:
        raw = fh.read()
    try:
        import yaml  # type: ignore
        return yaml.safe_load(raw) or {}
    except Exception:
        pass

    cfg = {'economy': {}, 'market': {}, 'database': {}, 'auction-house': {},
           'buy-orders': {}, 'custom-items': {}}

    def indent(pattern):
        # Explicit space class: \s would also match newlines and make these
        # indentation-sensitive patterns ambiguous.
        return pattern.replace('\\s', '[ \\t]')

    m = re.search(indent(r'^[ \t]*default-currency:[ \t]*"?([^"\n#]+)"?[ \t]*$'), raw, re.M)
    if m:
        cfg['economy']['default-currency'] = m.group(1).strip()

    # `$` under re.M matches *before* the newline, so a block capture has to
    # consume the line terminator explicitly before matching indented children.
    block = re.search(indent(r'^[ ]{2}currencies:[ \t]*\n((?:[ ]{4,}[^\n]*\n?)*)'),
                      raw, re.M)
    if block:
        cfg['economy']['currencies'] = {}
        for cm in re.finditer(indent(r'^[ ]{4}([A-Za-z0-9_ \-]+):[ \t]*$'), block.group(1), re.M):
            cfg['economy']['currencies'][cm.group(1).strip()] = {}

    m = re.search(indent(r'^[ \t]*starting-balance:[ \t]*([-\d.eE+]+)'), raw, re.M)
    if m:
        try:
            cfg['economy']['starting-balance'] = float(m.group(1))
        except ValueError:
            pass

    m = re.search(indent(r'^[ \t]*max-balance:[ \t]*([-\d.eE+]+)'), raw, re.M)
    if m:
        try:
            cfg['economy']['max-balance'] = float(m.group(1))
        except ValueError:
            pass

    m = re.search(indent(r'^[ \t]*file:[ \t]*"?([^"\n]+)"?[ \t]*$'), raw, re.M)
    if m:
        cfg['database']['file'] = m.group(1).strip()

    bl = re.search(indent(r'^[ ]{2}blacklist:[ \t]*\n((?:[ ]{4}-[ \t]*\S+[^\n]*\n?)*)'),
                   raw, re.M)
    if bl:
        cfg['market']['blacklist'] = [x.strip().upper() for x in
                                      re.findall(r'-\s*(\S+)', bl.group(1))]

    m = re.search(indent(r'^[ \t]*listing-fee-percent:[ \t]*([-\d.eE+]+)'), raw, re.M)
    if m:
        try:
            cfg['auction-house']['listing-fee-percent'] = float(m.group(1))
        except ValueError:
            pass
    return cfg



# ── backend-agnostic schema queries ─────────────────────────────────
#
# The suite originally spoke SQLite only. Under MySQL there is no file to open,
# so every persisted-state assertion returned None and the economy checks failed
# even though the plugin was writing correctly. These emit the right dialect for
# whichever backend AURELIUM_DB_HOST selects.

def _is_mysql():
    return bool(os.environ.get('AURELIUM_DB_HOST'))


def table_exists_sql(table):
    if _is_mysql():
        return (
            'SELECT table_name FROM information_schema.tables '
            f"WHERE table_schema = DATABASE() AND table_name = '{table}'"
        )
    return "SELECT name FROM sqlite_master WHERE type='table' " \
           f"AND name='{table}'"


def table_list_sql():
    if _is_mysql():
        return (
            'SELECT table_name FROM information_schema.tables '
            'WHERE table_schema = DATABASE()'
        )
    return "SELECT name FROM sqlite_master WHERE type='table'"


def columns_sql(table):
    """Column names as the FIRST field of each row, on either backend.

    SQLite's PRAGMA table_info returns (cid, name, type, ...) so the name is at
    index 1, while information_schema.columns returns the name at index 0.
    Selecting the name alone on both keeps the caller dialect-independent.
    """
    if _is_mysql():
        return (
            'SELECT column_name FROM information_schema.columns '
            f"WHERE table_schema = DATABASE() AND table_name = '{table}'"
        )
    return f'SELECT name FROM pragma_table_info(\'{table}\')'


def create_sql(table):
    if _is_mysql():
        return (
            'SELECT create_statement FROM information_schema.tables '
            f"WHERE table_schema = DATABASE() AND table_name = '{table}'"
        )
    return "SELECT sql FROM sqlite_master WHERE type='table' " \
           f"AND name='{table}'"

def mysql_config_from_env():
    """Return MySQL connection details when the suite is pointed at a server.

    Set by the mysql-test job. Returns None for the SQLite-backed jobs.
    """
    host = os.environ.get('AURELIUM_DB_HOST')
    if not host:
        return None
    return {
        'host': host,
        'port': os.environ.get('AURELIUM_DB_PORT', '3306'),
        'user': os.environ.get('AURELIUM_DB_USER', 'root'),
        'password': os.environ.get('AURELIUM_DB_PASSWORD', 'test'),
        'name': os.environ.get('AURELIUM_DB_NAME', 'aurelium_test'),
    }


class Database:
    """Read-only view of the plugin's database, with async-write polling.

    Supports both backends. Under SQLite it opens the plugin's ``.db`` file
    directly; under MySQL there is no file to open, so it queries the server
    through ``mysql`` on PATH (the mysql-test job runs one in Docker). Without
    this, every persisted-state assertion silently returned ``None`` under MySQL
    and the economy checks failed even though the plugin was writing correctly.
    """

    def __init__(self, path, expected_type=None):
        self.path = path
        self.mysql_config = mysql_config_from_env()
        self.conn = None
        self.available = False
        self._note = ''

        # The plugin's configured backend is the source of truth. If it disagrees
        # with what we were told to read, every assertion below is about to fail
        # for the wrong reason, so say so loudly rather than reporting "not found".
        if expected_type and self.mysql_config and expected_type.lower() != 'mysql':
            self._note = (f'plugin is configured for {expected_type!r} but '
                          f'AURELIUM_DB_HOST is set; refusing to read MySQL')
        elif expected_type and not self.mysql_config and expected_type.lower() == 'mysql':
            self._note = ('plugin is configured for mysql but AURELIUM_DB_HOST is '
                          'not set, so the suite would look for a SQLite file that '
                          'does not exist')

        if self._note:
            # Deliberately leave available False: pretending to work is worse.
            self.available = False
            return

        if self.mysql_config:
            self.available = shutil.which('mysql') is not None
            if not self.available:
                self._note = 'mysql client not on PATH'
        else:
            self.available = os.path.isfile(path)
            self._note = '' if self.available else f'not found at {path}'
            if self.available:
                try:
                    # Opened read/write rather than mode=ro: a WAL database needs a
                    # writable -shm index even for readers. The suite only issues
                    # SELECTs, and the file is only opened if it already exists.
                    self.conn = sqlite3.connect(path, timeout=20)
                    self.conn.row_factory = sqlite3.Row
                except sqlite3.Error as exc:
                    self.available = False
                    self._note = f'unopenable: {exc}'

    def close(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except sqlite3.Error:
                pass

    def _mysql_query(self, sql):
        """Run a SELECT through the mysql client and return list of tuples.

        The client is invoked per query rather than holding a driver connection,
        so there is no idle socket for MySQL's wait_timeout to close underneath
        the suite - the same failure the plugin itself had to guard against.
        """
        cfg = self.mysql_config
        cmd = [
            'mysql',
            f"--host={cfg['host']}",
            f"--port={cfg['port']}",
            f"--user={cfg['user']}",
            f"--password={cfg['password']}",
            '--batch', '--skip-column-names',
            cfg['name'],
            '--execute', sql,
        ]
        try:
            proc = subprocess.run(cmd, capture_output=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired):
            return []
        if proc.returncode != 0:
            return []
        rows = []
        for line in proc.stdout.decode('utf-8', errors='replace').splitlines():
            if not line.strip():
                continue
            rows.append(tuple(line.split('\t')))
        return rows

    def q(self, sql, args=()):
        if not self.available:
            return []
        if self.mysql_config:
            return self._mysql_query(sql)
        try:
            return self.conn.execute(sql, args).fetchall()
        except sqlite3.Error:
            return []

    def one(self, sql, args=()):
        rows = self.q(sql, args)
        return rows[0] if rows else None

    def scalar(self, sql, args=(), default=None):
        row = self.one(sql, args)
        if row is None:
            return default
        value = row[0]
        if self.mysql_config:
            # The client returns everything as text; the suite compares balances
            # against floats, so coerce the numeric shapes back.
            try:
                return float(value)
            except (TypeError, ValueError):
                return value
        return value

    def wait_for(self, predicate, timeout=25.0, interval=0.25):
        """Poll until predicate() is truthy. Returns its final value."""
        deadline = time.time() + timeout
        value = predicate()
        while not value and time.time() < deadline:
            time.sleep(interval)
            value = predicate()
        return value

    def journal_mode(self):
        if not self.available or self.mysql_config:
            # PRAGMA is SQLite-only.
            return None
        try:
            return self.scalar('PRAGMA journal_mode', default=None)
        except sqlite3.Error:
            return None


# ────────────────────────────── suite ──────────────────────────────

class Suite:
    def __init__(self):
        self.rcon = None
        self.db = None
        self.cfg = {}
        self.passed = 0
        self.failures = []
        self.skipped = []
        self.section = ''
        self._inj_offset = None

    # ── reporting ──
    def head(self, title):
        self.section = title
        print(f"\n=== {title} ===")

    def check(self, cond, msg, detail=''):
        label = f"{self.section}: {msg}"
        if cond:
            self.passed += 1
            print(f"PASS: {label}")
        else:
            self.failures.append((label, detail))
            print(f"FAIL: {label}" + (f"  [{detail}]" if detail else ''))
        return bool(cond)

    def skip(self, msg, why=''):
        label = f"{self.section}: {msg}"
        self.skipped.append((label, why))
        print(f"SKIP: {label}" + (f"  ({why})" if why else ''))

    def eq(self, actual, expected, msg):
        return self.check(actual == expected, msg,
                          f'expected {expected!r}, got {actual!r}')

    def near(self, actual, expected, msg, tol=0.005):
        ok = actual is not None and abs(float(actual) - float(expected)) <= tol
        return self.check(ok, msg, f'expected ~{expected}, got {actual!r}')

    # ── command helpers ──
    def cmd(self, c):
        return strip_color(self.rcon.send(c))

    def routed(self, c, label=None):
        """The command exists, dispatched, and was not rejected by the router."""
        label = label or f"/{c} is registered and dispatched"
        resp = self.cmd(c)
        low = resp.lower()
        hit = next((p for p in ROUTING_ERRORS if p in low), None)
        return self.check(hit is None, label,
                          f'response: {resp.strip()[:160]!r}')

    def console_only(self, c, label=None):
        """A player-only command must refuse the console without a routing error.

        The refusal text itself is not asserted: Paper forwards Adventure
        Components to the RCON client as plain text on some versions and drops
        them on others, so only the router-level guarantee is portable.
        """
        label = label or f"/{c} refuses the console sender"
        resp = self.cmd(c)
        low = resp.lower()
        hit = next((p for p in ROUTING_ERRORS if p in low), None)
        return self.check(hit is None, label, f'response: {resp.strip()[:160]!r}')

    # ── state helpers ──
    def uuid_of(self, name):
        """Resolve a player name to the UUID the plugin persisted for it.

        Prefers the ``players`` table, then the name-derived offline UUID that
        CraftBukkit uses when ``online-mode=false`` (MD5, version 3), then the
        single balance row that has no ``players`` entry.
        """
        row = self.db.one('SELECT uuid FROM players WHERE name = ?', (name,))
        if row is not None:
            return row['uuid']

        import hashlib
        try:
            digest = hashlib.md5(("OfflinePlayer:" + name).encode('utf-8'),
                                 usedforsecurity=False).digest()
        except TypeError:
            digest = hashlib.md5(("OfflinePlayer:" + name).encode('utf-8')).digest()
        b = bytearray(digest)
        b[6] = (b[6] & 0x0F) | 0x30
        b[8] = (b[8] & 0x3F) | 0x80
        hx = b.hex()
        derived = f"{hx[0:8]}-{hx[8:12]}-{hx[12:16]}-{hx[16:20]}-{hx[20:32]}"
        if self.db.one('SELECT 1 FROM player_balances WHERE uuid = ?', (derived,)):
            return derived

        orphans = [r['uuid'] for r in self.db.q(
            'SELECT DISTINCT uuid FROM player_balances '
            'WHERE uuid NOT IN (SELECT uuid FROM players)')]
        if len(orphans) == 1:
            return orphans[0]
        return derived

    def balance(self, name, currency):
        if not self.db.available:
            return None
        return self.db.scalar(
            'SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?',
            (self.uuid_of(name), currency))

    def wait_balance(self, name, currency, expected, timeout=25.0, tol=0.005):
        def probe():
            val = self.balance(name, currency)
            return val is not None and abs(float(val) - float(expected)) <= tol
        got = self.db.wait_for(probe, timeout=timeout)
        return self.check(bool(got), f'balance of {name} in {currency} is {expected}',
                          f'persisted {self.balance(name, currency)!r}')

    def unchanged(self, name, currency, before, label):
        val = self.wait_db_stable(name, currency)
        return self.check(val is not None and abs(float(val) - float(before)) <= 0.005,
                          label, f'before {before}, now {val!r}')

    def wait_db_stable(self, name, currency, timeout=8.0):
        """Wait until the persisted balance stops changing, then return it."""
        last = object()
        deadline = time.time() + timeout
        while time.time() < deadline:
            val = self.balance(name, currency)
            if val is not None and val == last:
                return val
            last = val
            time.sleep(0.5)
        return self.balance(name, currency)

    def log_text(self):
        if not os.path.isfile(LOG_PATH):
            return ''
        with open(LOG_PATH, 'r', encoding='utf-8', errors='replace') as fh:
            return fh.read()

    def log_size(self):
        return os.path.getsize(LOG_PATH) if os.path.isfile(LOG_PATH) else 0


# ── individual sections ─────────────────────────────────────────────

def sec_health(t):
    t.head('1. environment and plugin health')
    t.check(os.path.isdir(PLUGIN_DIR), 'plugin data folder exists', PLUGIN_DIR)
    t.check(os.path.isfile(CONFIG_PATH), 'config.yml exists', CONFIG_PATH)
    t.check(os.path.isfile(LOG_PATH), 'server log exists', LOG_PATH)

    log = t.log_text()
    t.check('AurelEconomy has been enabled' in log, 'plugin reports enabled')
    t.check('Vault hooked successfully' in log or 'Vault' in log,
            'Vault integration reported')
    t.check('Failed to initialize database' not in log,
            'database initialisation did not fail')
    t.check('Disabling plugin' not in log,
            'plugin was not self-disabled during startup')

    t.cmd('list')
    t.check(True, 'server still responsive after health probe')


def sec_schema(t):
    t.head('2. database schema')
    if not t.db.available:
        t.skip('schema assertions', t.db._note or 'SQLite backend not in use')
        return

    expected = ['auction_offers', 'auctions', 'buy_orders', 'custom_items',
                'database_info', 'offline_earnings', 'player_balances',
                'players', 'price_history']
    rows = t.db.q(table_list_sql())
    present = {r['name'] for r in rows}
    for table in expected:
        t.check(table in present, f'table {table} exists',
                f'present: {sorted(present)}')

    version = t.db.scalar('SELECT version FROM database_info LIMIT 1')
    t.check(version is not None, 'database_info records a schema version')

    # Compare against LATEST_SCHEMA_VERSION in DatabaseManager rather than a
    # hardcoded allowlist. The allowlist went stale at schema 5 and failed every
    # run from then on, including the run that introduced the bump.
    expected = None
    try:
        db_manager = os.path.join(SERVER_DIR, 'src', 'main', 'java', 'com',
                                  'aureleconomy', 'database', 'DatabaseManager.java')
        with open(db_manager, encoding='utf-8') as fh:
            m = re.search(r'LATEST_SCHEMA_VERSION\s*=\s*(\d+)', fh.read())
        expected = m.group(1) if m else None
    except OSError:
        pass
    t.check(expected is not None,
            'LATEST_SCHEMA_VERSION is readable from DatabaseManager.java')
    if expected is not None:
        t.check(str(version).lstrip('v') == expected,
                'database schema matches LATEST_SCHEMA_VERSION',
                f'found={version!r} expected={expected!r}')

    t.check(t.db.journal_mode() is not None, 'journal mode readable',
            f'journal_mode={t.db.journal_mode()!r}')

    columns = {
        'players': {'uuid', 'name', 'gui_style'},
        'player_balances': {'uuid', 'currency', 'balance'},
        'auctions': {'id', 'seller_uuid', 'item_data', 'price', 'currency', 'is_bin',
                     'expiration', 'highest_bidder_uuid', 'ended', 'collected',
                     'listing_fee', 'start_time', 'purchase_mode'},
        'auction_offers': {'id', 'auction_id', 'bidder_uuid', 'amount', 'currency',
                           'status', 'timestamp'},
        'buy_orders': {'id', 'buyer_uuid', 'material', 'amount_requested',
                       'amount_filled', 'price_per_piece', 'currency', 'status'},
        'price_history': {'id', 'item_key', 'buy_price', 'sell_price', 'timestamp'},
        'offline_earnings': {'id', 'uuid', 'amount', 'currency', 'item_display', 'timestamp'},
        'custom_items': {'canonical_id', 'source_plugin', 'display_name', 'item_data',
                         'pdc_key', 'model_data_key', 'lore_hash', 'plugin_native_id',
                         'category', 'buy_price', 'sell_price', 'enabled',
                         'discovery_methods', 'first_discovered', 'last_seen'},
    }
    for table, expected_cols in columns.items():
        if table not in present:
            continue
        cols = {r[0] for r in t.db.q(columns_sql(table))}
        missing = expected_cols - cols
        t.check(not missing, f'{table} has all expected columns', f'missing {sorted(missing)}')

    pk = t.db.q(create_sql('player_balances'))
    t.check(bool(pk) and 'PRIMARY KEY' in (pk[0]['sql'] or '').upper(),
            'player_balances declares a composite primary key')
    dupes = t.db.q('SELECT uuid, currency, COUNT(*) c FROM player_balances '
                   'GROUP BY uuid, currency HAVING c > 1')
    t.check(not dupes, 'player_balances has no duplicate (uuid, currency) pairs',
            f'{len(dupes)} duplicate group(s)')


def sec_economy(t):
    t.head('3. economy CRUD with database verification')
    econ = t.cfg.get('economy', {}) or {}
    default_currency = econ.get('default-currency') or 'Aurels'
    t.check(bool(default_currency), 'default currency resolved from config',
            f'default-currency={default_currency!r}')

    currencies = list((econ.get('currencies') or {}).keys()) or [default_currency]
    t.check(default_currency in currencies,
            'default currency is present in the currencies map',
            f'currencies={currencies}')
    cur = default_currency

    # --- rejections: each of these must leave the balance untouched ---
    baseline = 250.0
    t.cmd(f'eco set {PLAYER} {baseline} {cur}')
    t.wait_balance(PLAYER, cur, baseline)

    negatives = [
        ('eco give', f'eco give {PLAYER} -100 {cur}'),
        ('eco give zero', f'eco give {PLAYER} 0 {cur}'),
        ('eco take negative', f'eco take {PLAYER} -100 {cur}'),
        ('eco take zero', f'eco take {PLAYER} 0 {cur}'),
        ('eco set negative', f'eco set {PLAYER} -1 {cur}'),
        ('eco set zero', f'eco set {PLAYER} 0 {cur}'),
        ('eco give non-numeric', f'eco give {PLAYER} abc {cur}'),
        ('eco give NaN', f'eco give {PLAYER} NaN {cur}'),
        ('eco give Infinity', f'eco give {PLAYER} Infinity {cur}'),
        ('eco give empty amount', f'eco give {PLAYER} {cur}'),
        ('eco unknown action', f'eco burn {PLAYER} 100 {cur}'),
        ('eco unknown currency', f'eco give {PLAYER} 100 NoSuchCurrency'),
        ('eco missing args', 'eco give'),
        ('eco missing args 2', f'eco give {PLAYER}'),
        ('eco SQL in amount', f"eco give {PLAYER} 1); DROP TABLE player_balances;-- {cur}"),
    ]
    for label, command in negatives:
        t.routed(command, f'/eco rejects {label} without a routing error')
    t.unchanged(PLAYER, cur, baseline,
                'rejected /eco invocations left the balance untouched')
    t.check(t.db.one(table_exists_sql('player_balances')) is not None,
            'player_balances table survived the SQL-injection amount probe')

    # --- set / give / take arithmetic ---
    t.cmd(f'eco set {PLAYER} 1000 {cur}')
    t.wait_balance(PLAYER, cur, 1000.0)

    t.cmd(f'eco give {PLAYER} 500.25 {cur}')
    t.wait_balance(PLAYER, cur, 1500.25)

    t.cmd(f'eco take {PLAYER} 200.25 {cur}')
    t.wait_balance(PLAYER, cur, 1300.00)

    t.cmd(f'eco set {PLAYER} 1e2 {cur}')
    t.wait_balance(PLAYER, cur, 100.0)

    t.cmd(f'eco set {PLAYER} 0.005 {cur}')
    t.wait_balance(PLAYER, cur, 0.00)

    t.cmd(f'eco set {PLAYER} 0.015 {cur}')
    t.wait_balance(PLAYER, cur, 0.02)

    t.cmd(f'eco set {PLAYER} 1 {cur}')
    t.wait_balance(PLAYER, cur, 1.0)

    before = t.wait_db_stable(PLAYER, cur)
    t.cmd(f'eco take {PLAYER} 999999 {cur}')
    t.unchanged(PLAYER, cur, before,
                'over-withdraw is refused and the balance stays intact')
    t.check('Insufficient funds for withdraw' in t.log_text(),
            'over-withdraw is logged as a warning')

    neg_after = t.balance(PLAYER, cur)
    t.check(neg_after is not None and float(neg_after) >= 0,
            'balance never goes negative', f'balance={neg_after!r}')

    # --- withdraw down to exactly zero ---
    t.cmd(f'eco set {PLAYER} 5 {cur}')
    t.wait_balance(PLAYER, cur, 5.0)
    t.cmd(f'eco take {PLAYER} 5 {cur}')
    t.wait_balance(PLAYER, cur, 0.0)

    # --- players metadata is written alongside balances ---
    t.check(t.db.one('SELECT uuid FROM players WHERE name = ?', (PLAYER,)) is not None,
            'players row recorded for the mutated account')

    # --- default starting balance is applied to a fresh currency ---
    start = econ.get('starting-balance', 100.0)
    t.cmd(f'bal {PLAYER2} {cur}')
    fresh = t.db.wait_for(lambda: t.balance(PLAYER2, cur))
    t.check(fresh is not None, f'fresh account {PLAYER2} has a persisted balance',
            f'balance={fresh!r}')
    t.near(fresh, start, f'fresh account starts from configured starting-balance ({start})')

    # --- multi-currency, only when the config actually defines more than one ---
    if len(currencies) > 1:
        for other in currencies:
            if other == cur:
                continue
            t.cmd(f'eco set {PLAYER} 777 {other}')
            t.wait_balance(PLAYER, other, 777.0)
        t.check(len(currencies) > 1, f'multi-currency balances are independent ({currencies})')
    else:
        t.skip('multi-currency isolation', 'config defines a single currency')

    return cur


def sec_concurrency(t, cur):
    t.head('4. concurrent economy writes')
    if not t.db.available:
        t.skip('concurrency', t.db._note or 'SQLite backend not in use')
        return

    t.cmd(f'eco set {PLAYER} 0 {cur}')
    t.wait_balance(PLAYER, cur, 0.0)

    for _ in range(CONCURRENCY_STEPS):
        t.cmd(f'eco give {PLAYER} 1 {cur}')

    expected = float(CONCURRENCY_STEPS)
    t.wait_balance(PLAYER, cur, expected, timeout=40.0)
    final = t.balance(PLAYER, cur)
    t.check(final is not None and abs(float(final) - expected) <= 0.005,
            f'{CONCURRENCY_STEPS} concurrent deposits total exactly {expected}',
            f'persisted {final!r} (lost updates if lower)')


def sec_auction(t):
    t.head('5. auction house')
    before_auctions = t.db.scalar('SELECT COUNT(*) FROM auctions', default=0) \
        if t.db.available else None
    before_offers = t.db.scalar('SELECT COUNT(*) FROM auction_offers', default=0) \
        if t.db.available else None

    t.console_only('ah', '/ah refuses the console sender')
    for sub in ['collect', 'offers', 'search', 'sell', 'bin', 'bid', 'cancel',
                'offer', 'bogus']:
        t.console_only(f'ah {sub}', f'/ah {sub} refuses the console sender')

    t.routed('ah sell', '/ah sell without a price shows usage rather than a routing error')
    t.routed('ah sell -100', '/ah sell with a negative price is handled')
    t.routed('ah sell 0', '/ah sell with a zero price is handled')
    t.routed('ah cancel', '/ah cancel without an id shows usage')
    t.routed('ah cancel abc', '/ah cancel with a non-numeric id is handled')
    t.routed('ah cancel 99999', '/ah cancel with an unknown id is handled')
    t.routed('ah cancel -1', '/ah cancel with a negative id is handled')
    t.routed('ah cancel 99999999999999', '/ah cancel with an overflowing id is handled')
    t.routed('ah offer 99999 100', '/ah offer on an unknown auction is handled')
    t.routed('ah offer 1 abc', '/ah offer with a non-numeric amount is handled')
    t.routed('ah offer -5 100', '/ah offer with a negative amount is handled')
    t.routed('ah offer 1 0', '/ah offer with a zero amount is handled')
    t.routed('ah search', '/ah search without a query shows usage')
    t.routed("ah search '; DROP TABLE auctions;--", '/ah search with SQL in the query is handled')
    t.routed('ah search ' + 'x' * 512, '/ah search with a very long query is handled')

    if t.db.available:
        t.check(t.db.scalar('SELECT COUNT(*) FROM auctions', default=0) == before_auctions,
                'no auction rows created by console-sourced /ah invocations')
        t.check(t.db.scalar('SELECT COUNT(*) FROM auction_offers', default=0) == before_offers,
                'no offer rows created by console-sourced /ah invocations')
        t.check(t.db.one(table_exists_sql('auctions')) is not None,
                'auctions table survived the /ah search injection probe')
        bad = t.db.q('SELECT id, expiration, start_time FROM auctions')
        t.check(all(r['expiration'] > r['start_time'] for r in bad),
                'every stored auction expires after it starts', f'{len(bad)} row(s)')
        modes = {r[0] for r in t.db.q('SELECT DISTINCT purchase_mode FROM auctions')}
        t.check(modes <= {'STACK', 'UNIT', None, ''},
                'purchase_mode column only holds known values', f'found {sorted(map(str, modes))}')
        statuses = {r[0] for r in t.db.q('SELECT DISTINCT status FROM auction_offers')}
        t.check(statuses <= {'PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', None, ''},
                'offer status column only holds known values', f'found {sorted(map(str, statuses))}')

    fee = (t.cfg.get('auction-house') or {}).get('listing-fee-percent')
    if fee is not None:
        t.check(fee > 0, f'configured listing-fee-percent is sane ({fee})')
    else:
        t.skip('listing-fee-percent sanity', 'key not found in config.yml')


def sec_orders(t):
    t.head('6. buy orders')
    before = t.db.scalar('SELECT COUNT(*) FROM buy_orders', default=0) \
        if t.db.available else None

    t.console_only('orders', '/orders refuses the console sender')
    for sub in ['create', 'fill', 'cancel', 'my', 'search', 'help', 'bogus']:
        t.console_only(f'orders {sub}', f'/orders {sub} refuses the console sender')

    t.routed('orders create', '/orders create without args shows usage')
    t.routed('orders create INVALID_MATERIAL 10 5', '/orders create rejects an unknown material')
    t.routed('orders create DIAMOND -10 5', '/orders create rejects a negative amount')
    t.routed('orders create DIAMOND 0 5', '/orders create rejects a zero amount')
    t.routed('orders create DIAMOND 10 0', '/orders create rejects a zero price')
    t.routed('orders create DIAMOND 10 -5', '/orders create rejects a negative price')
    t.routed('orders create DIAMOND 10 abc', '/orders create rejects a non-numeric price')
    t.routed('orders create DIAMOND 10 1 extra', '/orders create tolerates extra arguments')
    t.routed('orders cancel', '/orders cancel without an id shows usage')
    t.routed('orders cancel abc', '/orders cancel with a non-numeric id is handled')
    t.routed('orders cancel 99999', '/orders cancel with an unknown id is handled')
    t.routed('orders fill 99999', '/orders fill with an unknown id is handled')
    t.routed('orders fill abc', '/orders fill with a non-numeric id is handled')
    t.routed('orders search', '/orders search without a query shows usage')
    t.routed("orders search '; DROP TABLE buy_orders;--", '/orders search with SQL is handled')

    if t.db.available:
        t.check(t.db.scalar('SELECT COUNT(*) FROM buy_orders', default=0) == before,
                'no order rows created by console-sourced /orders invocations')
        t.check(t.db.one(table_exists_sql('buy_orders')) is not None,
                'buy_orders table survived the /orders search injection probe')
        bad = t.db.q('SELECT id, amount_requested, amount_filled FROM buy_orders')
        t.check(all(r['amount_filled'] <= r['amount_requested'] for r in bad),
                'no order is filled beyond what was requested', f'{len(bad)} row(s)')
        t.check(all(float(r['price_per_piece'] or 0) >= 0 for r in bad),
                'no order has a negative price')


def sec_custom_items(t):
    t.head('7. custom item scanner')
    t.routed('customitems', '/customitems without a subcommand shows usage')
    t.routed('customitems bogus', '/customitems with an unknown subcommand shows usage')
    t.routed('customitems info', '/customitems info without an id shows usage')
    t.routed('customitems toggle', '/customitems toggle without an id shows usage')
    t.routed('customitems price', '/customitems price without arguments shows usage')
    t.routed('customitems price only_id', '/customitems price with too few arguments shows usage')
    t.routed('customitems info nonexistent_item', '/customitems info on an unknown id is handled')
    t.routed('customitems toggle nonexistent_item', '/customitems toggle on an unknown id is handled')
    t.routed('customitems price nonexistent_item -5 10', '/customitems price rejects a negative buy')
    t.routed('customitems price nonexistent_item 10 -5', '/customitems price rejects a negative sell')
    t.routed('customitems price nonexistent_item NaN 10', '/customitems price rejects NaN')
    t.routed('customitems price nonexistent_item Infinity 10', '/customitems price rejects Infinity')

    # Page clamping: 0, negative and absurd page numbers must not crash the
    # (0-based) slicing in CustomItemsCommand.handleList.
    for page in ['0', '-5', '1', '999999', 'abc']:
        t.routed(f'customitems list {page}', f'/customitems list {page} is handled')

    t.routed('customitems scan', '/customitems scan triggers a rescan')
    time.sleep(3.0)
    t.routed('customitems reload', '/customitems reload re-reads the database')
    time.sleep(3.0)

    if not t.db.available:
        t.skip('custom item state', t.db._note or 'SQLite backend not in use')
        return

    items = t.db.q('SELECT canonical_id, enabled, buy_price, sell_price, '
                   'first_discovered, last_seen FROM custom_items')
    t.check(True, f'custom_items readable ({len(items)} row(s))')
    t.check(all(r['first_discovered'] > 0 for r in items),
            'every discovered item has a first_discovered timestamp')
    t.check(all(r['last_seen'] >= r['first_discovered'] for r in items),
            'last_seen is never before first_discovered')
    t.check(all(float(r['buy_price']) >= -1 for r in items),
            'buy_price never below the -1 "unset" sentinel')
    t.check(all(float(r['sell_price']) >= -1 for r in items),
            'sell_price never below the -1 "unset" sentinel')
    t.check(all(r['enabled'] in (0, 1, None) for r in items),
            'enabled column is a 0/1 flag')

    if not items:
        t.skip('custom item price/toggle round trip',
               'no custom item plugin installed in this job, so nothing is discovered')
        return

    cid = items[0]['canonical_id']
    t.routed(f'customitems info {cid}', f'/customitems info {cid} resolves a real item')

    def price_row():
        return t.db.one('SELECT buy_price, sell_price FROM custom_items '
                        'WHERE canonical_id = ?', (cid,))

    t.cmd(f'customitems price {cid} 123.5 45.25')
    got = t.db.wait_for(lambda: (
        lambda r: r if r and abs(float(r['buy_price']) - 123.5) <= 0.01
        and abs(float(r['sell_price']) - 45.25) <= 0.01 else None)(price_row()))
    row = price_row()
    t.check(bool(got), f'/customitems price {cid} persisted to the database',
            f'row={dict(row) if row is not None else None}')

    enabled_before = t.db.scalar('SELECT enabled FROM custom_items WHERE canonical_id = ?', (cid,))
    t.cmd(f'customitems toggle {cid}')
    toggled = t.db.wait_for(
        lambda: (lambda r: r if r and r['enabled'] != enabled_before else None)(
            t.db.one('SELECT enabled FROM custom_items WHERE canonical_id = ?', (cid,))))
    t.check(bool(toggled), f'/customitems toggle {cid} flipped the persisted flag',
            f'before={enabled_before!r}')

    t.cmd(f'customitems toggle {cid}')
    t.db.wait_for(
        lambda: t.db.scalar('SELECT enabled FROM custom_items WHERE canonical_id = ?',
                            (cid,)) == enabled_before)


def sec_player_only(t):
    t.head('8. player-only commands (console must be refused)')
    t.console_only('pay Console 10', '/pay refuses the console sender')
    t.console_only('pay', '/pay without args refuses the console sender')
    t.console_only('market', '/market refuses the console sender')
    t.console_only('sell', '/sell refuses the console sender')
    t.console_only('stocks', '/stocks refuses the console sender')
    t.console_only('web', '/web refuses the console sender')

    t.routed('bal', '/bal without args is handled by the console branch')
    t.routed('bal Console', '/bal with a player name is dispatched')
    t.routed('bal Console Aurels', '/bal with a player and currency is dispatched')
    t.routed(f'bal {PLAYER} NoSuchCurrency', '/bal with an unknown currency is handled')
    t.routed('bal ' + 'n' * 200, '/bal with a very long name is handled')
    t.routed("bal x'; DROP TABLE players;--", '/bal with SQL in the name is handled')

    # /bal against a bogus currency creates a row for that currency; ensure the
    # table is intact and no players rows were lost.
    if t.db.available:
        t.check(t.db.one(table_exists_sql('players')) is not None,
                'players table survived the /bal injection probe')


def sec_adversarial(t):
    t.head('9. adversarial input')
    # Everything from here on is expected to be rejected by validation, so any
    # Aurelium stack frame in the log after this offset is treated as a warning
    # rather than a hard failure.
    t._inj_offset = t.log_size()

    t.routed('eco give', 'bare /eco give does not throw')
    t.routed('eco', 'bare /eco does not throw')
    t.routed('bal ' + 'a' * 3000, 'absurdly long /bal argument does not throw')
    t.routed('ah search ' + '\u00e9\u4f60\u597d\U0001f600', 'unicode /ah search is handled')
    t.routed('orders search \U0001f600\U0001f680', 'emoji /orders search is handled')
    t.routed('customitems info ' + '\u00e9' * 64, 'unicode /customitems info is handled')
    t.routed('customitems price x y z', 'non-numeric /customitems price is handled')
    t.routed('list', 'vanilla /list still works after the probe')

    # A hostile but correctly-sized player name reaches getOfflinePlayer() and is
    # then bound as a PreparedStatement parameter. The tables must survive.
    if t.db.available:
        tables_before = len(t.db.q(table_list_sql()))
        rows_before = t.db.scalar('SELECT COUNT(*) FROM players', default=0)
        t.cmd(f'eco set {INJECT_NAME} 42')
        time.sleep(2.5)
        tables_after = len(t.db.q(table_list_sql()))
        rows_after = t.db.scalar('SELECT COUNT(*) FROM players', default=0)
        t.check(tables_after == tables_before,
                'injected player name did not drop any table',
                f'{tables_before} -> {tables_after}')
        t.check(rows_after in (rows_before, rows_before + 1),
                'injected player name created at most one player row',
                f'{rows_before} -> {rows_after}')
        t.check(t.db.scalar('SELECT balance FROM player_balances WHERE currency IS NULL',
                            default=0) == 0,
                'no balance row has a null currency')


def iter_log_records(text):
    """Split a log into records of (header, [continuation lines]).

    Java prints a stack trace across many lines, so a naive per-line scan
    cannot tell which frames belong to which message. Attribution is what lets
    the benign list suppress the frames of a handled error without hiding a
    genuine crash elsewhere.
    """
    record = None
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        if STACK_CONTINUATION.match(stripped) and record is not None:
            record[1].append(stripped)
            continue
        if record is not None:
            yield record
        record = (stripped, [])
    if record is not None:
        yield record


def sec_log_audit(t):
    t.head('10. server log audit')
    if not os.path.isfile(LOG_PATH):
        t.skip('log audit', 'log file missing')
        return

    with open(LOG_PATH, 'r', encoding='utf-8', errors='replace') as fh:
        log = fh.read()
    offset = t._inj_offset or 0
    regions = (('before the adversarial section', log[:offset], True),
               ('during the adversarial section', log[offset:], False))

    for label, text, hard in regions:
        hits, soft = [], []
        for header, frames in iter_log_records(text):
            record = header + ' ' + ' '.join(frames)
            if BENIGN_LOG.search(record):
                continue
            if FATAL_LOG.search(record):
                hits.append(header[:200])
            elif 'at com.aureleconomy' in record:
                soft.append(header[:200])

        if hard:
            t.check(not hits, f'no crash signature in the log {label}',
                    f'{len(hits)} record(s); first: {hits[0]!r}' if hits else '')
        else:
            # Provoked region: Aurelium frames from a rejected input are expected.
            t.check(not hits, f'no crash signature in the log {label}',
                    f'{len(hits)} record(s); first: {hits[0]!r}' if hits else '')
        for s in soft:
            print(f"      tolerated: {s}")

    t.check('AurelEconomy has been enabled' in log, 'plugin is enabled in the log')


def _read_state():
    if not os.path.isfile(STATE_PATH):
        return {}
    try:
        with open(STATE_PATH, 'r', encoding='utf-8') as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def sec_restart_verify(t, cur):
    """Phase 2: assert the previous run's balance survived the reboot.

    Must run *before* the mutating economy sections, otherwise they overwrite the
    very value being verified.
    """
    t.head('11a. restart persistence (verify)')
    state = _read_state()
    if state.get('phase') != 1:
        t.skip('balance survived the restart', 'first run in this server directory')
        return
    expected = state.get('expected_balance')
    currency = state.get('currency') or cur
    if not t.db.available:
        t.skip('restart persistence', t.db._note or 'SQLite backend not in use')
        _write_state({'phase': 2, 'expected_balance': expected, 'currency': currency})
        return

    got = t.balance(PLAYER, currency)
    t.check(got is not None and abs(float(got) - float(expected)) <= 0.005,
            f'balance {expected} survived the server restart',
            f'after restart: {got!r}')
    t.check(t.db.scalar('SELECT COUNT(*) FROM auctions', default=0) is not None,
            'auctions table is readable after restart')
    t.check(t.db.scalar('SELECT COUNT(*) FROM buy_orders', default=0) is not None,
            'buy_orders table is readable after restart')
    t.check('AurelEconomy has been enabled' in t.log_text(),
            'plugin re-enabled on the second boot')
    t.cmd(f'bal {PLAYER} {currency}')
    time.sleep(2.0)
    t.check(t.balance(PLAYER, currency) is not None,
            'balance still resolvable through the plugin after restart')
    _write_state({'phase': 2, 'expected_balance': expected, 'currency': currency})


def sec_restart_record(t, cur):
    """Phase 1: pin a known balance so the next run can prove it persisted.

    Runs last so the recorded value is whatever the suite actually left behind.
    """
    t.head('11b. restart persistence (record for the next run)')
    if _read_state().get('phase') == 2:
        t.skip('recording a balance for the next run',
               'this server directory already ran the restart pair')
        return
    if not t.db.available:
        t.skip('restart persistence', t.db._note or 'SQLite backend not in use')
        return

    expected = 12345.67
    t.cmd(f'eco set {PLAYER} {expected} {cur}')
    t.wait_balance(PLAYER, cur, expected)
    _write_state({'phase': 1, 'expected_balance': expected, 'currency': cur})
    t.check(True, f'phase 1 recorded expected balance {expected} for the restart run')


def _write_state(state):
    try:
        with open(STATE_PATH, 'w', encoding='utf-8') as fh:
            json.dump(state, fh)
    except OSError as exc:
        print(f"WARN: could not write {STATE_PATH}: {exc}")


def sec_summary(t):
    t.head('summary')
    total = t.passed + len(t.failures)
    print(f"\n{'=' * 60}")
    print(f"passed : {t.passed}/{total}")
    print(f"skipped: {len(t.skipped)}")
    print(f"failed : {len(t.failures)}")
    if t.skipped:
        print("\nSkipped:")
        for label, why in t.skipped:
            print(f"  - {label}" + (f" ({why})" if why else ''))
    if t.failures:
        print("\nFailures:")
        for label, detail in t.failures:
            print(f"  - {label}" + (f"  [{detail}]" if detail else ''))
    print(f"{'=' * 60}")


def main():
    t = Suite()
    t.cfg = load_config()
    db_file = (t.cfg.get('database') or {}).get('file') or 'database.db'
    db_type = (t.cfg.get('database') or {}).get('type', 'sqlite')
    t.db = Database(os.path.join(PLUGIN_DIR, db_file), expected_type=db_type)
    if not t.db.available and t.db._note:
        # Loud on purpose: a mis-wired harness reads as "the plugin is broken"
        # when the truth is that the suite cannot see the database at all.
        print(f"WARNING: persisted-state verification is unavailable: {t.db._note}",
              file=sys.stderr)

    print(f"server dir : {SERVER_DIR}")
    print(f"plugin dir : {PLUGIN_DIR}")
    print(f"database   : {t.db.path} "
          f"({'readable' if t.db.available else t.db._note})")

    try:
        t.rcon = Rcon(RCON_HOST, RCON_PORT, RCON_PASSWORD)
    except (OSError, RconError) as exc:
        print(f"FAIL: cannot open RCON at {RCON_HOST}:{RCON_PORT}: {exc}")
        return 1
    print("PASS: RCON authenticated")

    cur = (t.cfg.get('economy') or {}).get('default-currency') or 'Aurels'
    try:
        sec_health(t)
        sec_schema(t)
        sec_restart_verify(t, cur)
        cur = sec_economy(t) or cur
        sec_concurrency(t, cur)
        sec_auction(t)
        sec_orders(t)
        sec_custom_items(t)
        sec_player_only(t)
        sec_adversarial(t)
        sec_log_audit(t)
        sec_restart_record(t, cur)
    except KeyboardInterrupt:
        print("\ninterrupted")
        return 1
    except Exception as exc:  # a crash in the suite is itself a failure
        import traceback
        traceback.print_exc()
        t.failures.append((f'{t.section}: suite crashed', f'{type(exc).__name__}: {exc}'))
    finally:
        sec_summary(t)
        if t.rcon is not None:
            t.rcon.close()
        t.db.close()

    return 1 if t.failures else 0


if __name__ == '__main__':
    sys.exit(main())

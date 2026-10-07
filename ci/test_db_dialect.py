"""Verify the harness's dialect selection and SQL generation.

Real MySQL is unavailable locally, so this checks the two things that can be
tested without a server: the correct dialect is chosen per backend, and the
SQLite path (used by every other job) is unchanged.
"""
import importlib.util
import os
import shutil
import sys

# Resolve the repo from this file rather than hardcoding a path: the check runs
# on Linux CI, where a Windows path does not exist.
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location(
    "test_rcon", os.path.join(REPO, "test_rcon.py")
)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

failures = []


def check(name, cond, detail=""):
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + (f" :: {detail}" if detail else ""))
    if not cond:
        failures.append(name)


print("=== default (no env) must stay SQLite: every other job depends on it ===")
os.environ.pop("AURELIUM_DB_HOST", None)
for fn, arg in [(mod.table_exists_sql, "players"),
                (mod.table_list_sql, None),
                (mod.columns_sql, "players"),
                (mod.create_sql, "players")]:
    sql = fn(arg) if arg else fn()
    # PRAGMA table_info is unusable here because it puts the column *id* first;
    # the SQLite branch selects the name alone instead.
    check(f"{fn.__name__} stays on SQLite",
          "information_schema" not in sql, sql[:70])

print("\n=== mysql-test env selects information_schema ===")
os.environ["AURELIUM_DB_HOST"] = "127.0.0.1"
for fn, arg in [(mod.table_exists_sql, "players"),
                (mod.table_list_sql, None),
                (mod.columns_sql, "players"),
                (mod.create_sql, "players")]:
    sql = fn(arg) if arg else fn()
    check(f"{fn.__name__} uses information_schema",
          "information_schema" in sql and "sqlite_master" not in sql, sql[:70])
    check(f"{fn.__name__} scopes to the active schema", "DATABASE()" in sql, sql[:70])

print("\n=== table name is interpolated, not dropped ===")
os.environ.pop("AURELIUM_DB_HOST", None)
check("table_exists_sql includes the name", "players" in mod.table_exists_sql("players"))
os.environ["AURELIUM_DB_HOST"] = "127.0.0.1"
check("mysql table_exists_sql includes the name",
      "players" in mod.table_exists_sql("players"))
check("mysql columns_sql includes the name",
      "players" in mod.columns_sql("players"))

print("\n=== mysql_config_from_env ===")
cfg = mod.mysql_config_from_env()
check("returns a config when host is set", cfg is not None and cfg["port"] == "3306", str(cfg))
os.environ.pop("AURELIUM_DB_HOST", None)
check("returns None without the env var", mod.mysql_config_from_env() is None)

print("\n=== Database construction ===")
os.environ["AURELIUM_DB_HOST"] = "127.0.0.1"
db = mod.Database("plugins/Aurelium/database.db")
check("mysql mode selected", db.mysql_config is not None)
check("sqlite conn not opened in mysql mode", db.conn is None)
db.close()

os.environ.pop("AURELIUM_DB_HOST", None)
db2 = mod.Database(os.path.join(os.sep, "nonexistent-aurelium-check", "database.db"))
check("sqlite mode selected", db2.mysql_config is None)
check("missing sqlite file marks unavailable", db2.available is False, db2._note)
db2.close()

print("\n=== a harness/backend mismatch must refuse rather than read nothing ===")
os.environ["AURELIUM_DB_HOST"] = "127.0.0.1"
mismatch = mod.Database("x.db", expected_type="sqlite")
check("plugin=sqlite but env says mysql -> unavailable",
      mismatch.available is False, mismatch._note)
mismatch.close()

os.environ.pop("AURELIUM_DB_HOST", None)
missing = mod.Database(os.path.join(os.sep, "nope-aurelium", "database.db"),
                       expected_type="mysql")
check("plugin=mysql but env missing -> says so, not 'not found at'",
      missing.available is False and "AURELIUM_DB_HOST" in missing._note, missing._note)
missing.close()

os.environ["AURELIUM_DB_HOST"] = "127.0.0.1"
agree = mod.Database("x.db", expected_type="mysql")
check("plugin=mysql and env set -> no mismatch complaint",
      "refusing" not in agree._note and "not set" not in agree._note, agree._note)
agree.close()

print("\n=== scalar coerces MySQL text to float ===")
os.environ["AURELIUM_DB_HOST"] = "127.0.0.1"
db3 = mod.Database("x.db")
# The mysql client is not installed here, so Database marks itself unavailable
# and q() short-circuits to []. Force the flag so the coercion itself is tested;
# whether a client exists is asserted separately above.
db3.available = True
db3._mysql_query = lambda sql: [("1250.5",)]
v = db3.scalar("SELECT balance FROM player_balances")
check("numeric text becomes float", isinstance(v, float) and v == 1250.5, repr(v))
db3._mysql_query = lambda sql: [("Steve",)]
v2 = db3.scalar("SELECT name FROM players")
check("non-numeric text stays a string", v2 == "Steve", repr(v2))
db3._mysql_query = lambda sql: []
check("empty result returns the default", db3.scalar("SELECT 1", default="dflt") == "dflt")
check("journal_mode is None under mysql", db3.journal_mode() is None)
db3.close()

print("\n=== without a mysql client the suite must report unavailable, not lie ===")
if shutil.which("mysql") is None:
    db4 = mod.Database("x.db")
    check("unavailable without the client", db4.available is False, db4._note)
    check("reads return nothing rather than raising", db4.q("SELECT 1") == [])
    db4.close()
else:
    print("  SKIP (mysql client present on this machine)")

print("\n=== SQLite column names must come back as names, not ids ===")
# PRAGMA table_info returns (cid, name, type, ...) so index 0 is the column id.
# Selecting the name alone on both backends keeps the caller dialect-independent.
import sqlite3
import tempfile

os.environ.pop("AURELIUM_DB_HOST", None)
_tmpdir = tempfile.mkdtemp()
_dbpath = os.path.join(_tmpdir, "dialect.db")
_conn = sqlite3.connect(_dbpath)
_conn.execute("CREATE TABLE auction_offers (id INTEGER, auction_id INTEGER, amount REAL)")
_conn.commit()
_conn.close()

_db = mod.Database(_dbpath)
_rows = _db.q(mod.columns_sql("auction_offers"))
_names = {r[0] for r in _rows}
check("sqlite column names are names, not ids",
      _names == {"id", "auction_id", "amount"}, str(sorted(_names)))
check("no column id leaked into the set", 0 not in _names or len(_names) == 3, str(_names))
_db.close()

print()
if failures:
    print(f"{len(failures)} FAILED: {failures}")
    sys.exit(1)
print("ALL DIALECT TESTS PASS")
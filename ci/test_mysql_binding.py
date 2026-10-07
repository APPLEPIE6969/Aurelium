"""Guard the MySQL parameter binding in test_rcon.py.

The suite verifies persisted state by querying the plugin's database. Under
MySQL that query goes through the ``mysql`` client via ``--execute``, so there is
no driver to bind parameters, and ``Database`` substitutes them itself.

That substitution used to be missing: ``q()`` forwarded the SQL but dropped
``args``, so every parameterised query reached the server with a literal ``?``,
failed, and was reported as an empty result. The economy, concurrency and
restart sections then failed with ``persisted None`` while the plugin was in
fact writing correctly.

These checks pin the behaviour so the bug cannot come back unnoticed, and so the
escaping is obviously correct to anyone reading it. They need no database: they
exercise the binder directly.
"""
import ast
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SUITE = os.path.join(os.path.dirname(HERE), "test_rcon.py")

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'' if ok else '  -> ' + detail}")
    if not ok:
        failures.append(label)


spec = importlib.util.spec_from_file_location("test_rcon", SUITE)
tr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tr)
bind = tr.Database._bind

# ── substitution ───────────────────────────────────────────────────────────

CASES = [
    # The exact shape balance() uses, i.e. the query that produced "persisted None".
    ("SELECT balance FROM player_balances WHERE uuid = ? AND currency = ?",
     ("u-1", "Aurels"),
     "SELECT balance FROM player_balances WHERE uuid = 'u-1' AND currency = 'Aurels'"),
    ("SELECT 1 WHERE a = ? AND b = ? AND c = ?", (1, 2.5, None),
     "SELECT 1 WHERE a = 1 AND b = 2.5 AND c = NULL"),
    # A value that would close the literal and inject SQL if not escaped.
    ("SELECT ?", ("O'Brien",), "SELECT 'O\\'Brien'"),
    ("SELECT ?", ("back\\slash",), "SELECT 'back\\\\slash'"),
    ("SELECT ?", ("line\nbreak",), "SELECT 'line\\nbreak'"),
    # A ? inside a quoted literal is data, not a placeholder.
    ("SELECT 'literal ? here' WHERE a = ?", ("x",),
     "SELECT 'literal ? here' WHERE a = 'x'"),
    ("SELECT `we?ird` FROM t WHERE a = ?", ("x",),
     "SELECT `we?ird` FROM t WHERE a = 'x'"),
    ("SELECT ?", (True,), "SELECT 1"),
    ("SELECT ?", (b"\x00\xff",), "SELECT X'00ff'"),
    ("SELECT 1", (), "SELECT 1"),
]

for sql, args, want in CASES:
    try:
        got = bind(sql, args)
    except Exception as exc:  # noqa: BLE001
        check(f"bind {sql[:44]!r} + {args!r}", False, repr(exc))
        continue
    check(f"bind {sql[:44]!r} + {args!r}", got == want,
          f"want {want!r} got {got!r}")

# A placeholder count that disagrees with the arguments must be an error, not a
# query that quietly matches nothing.
for sql, args in [("SELECT ?", ("a", "b")),
                  ("SELECT ? WHERE a = ?", ("only",))]:
    try:
        bind(sql, args)
    except ValueError:
        check(f"arity mismatch raises: {sql[:36]!r}", True)
    else:
        check(f"arity mismatch raises: {sql[:36]!r}", False, "no error")

# ── q() must forward args ───────────────────────────────────────────────────
# The original defect, pinned directly: q() silently ignored its args.
import inspect  # noqa: E402

q_src = inspect.getsource(tr.Database.q)
check("q() forwards args to the mysql path",
      "self._mysql_query(sql, args)" in q_src,
      "args are dropped again")

mq_src = inspect.getsource(tr.Database._mysql_query)
check("_mysql_query binds before executing", "_bind(sql" in mq_src)
check("_mysql_query records failures", "last_error" in mq_src)

# ── every parameterised call must line up ───────────────────────────────────
# Call sites are split across lines, so this is an AST walk rather than a grep.
tree = ast.parse(open(SUITE, encoding="utf-8").read(), SUITE)
mismatched = []
counted = 0
for node in ast.walk(tree):
    if not isinstance(node, ast.Call):
        continue
    f = node.func
    if not (isinstance(f, ast.Attribute) and f.attr in ("q", "one", "scalar")):
        continue
    if not (isinstance(f.value, ast.Attribute) and f.value.attr == "db"):
        continue
    if len(node.args) < 2:
        continue
    first = node.args[0]
    if not (isinstance(first, ast.Constant) and isinstance(first.value, str)):
        continue
    counted += 1
    placeholders = first.value.count("?")
    supplied = 0
    for extra in node.args[1:]:
        supplied += len(extra.elts) if isinstance(extra, ast.Tuple) else 1
    if supplied != placeholders:
        mismatched.append(
            f"line {node.lineno}: {placeholders} placeholder(s) vs {supplied} arg(s)")

check(f"all {counted} parameterised db calls balance their placeholders",
      not mismatched, "; ".join(mismatched))

# ── SQLite-only assertions must not run under MySQL ─────────────────────────
schema_src = open(SUITE, encoding="utf-8").read()
check("journal_mode assertion is guarded for MySQL",
      "_is_mysql()" in schema_src and "PRAGMA is SQLite-only" in schema_src,
      "SQLite-only PRAGMA check runs under MySQL")
check("DDL comes from SHOW CREATE TABLE, not a nonexistent column",
      "SHOW CREATE TABLE" in schema_src
      and "SELECT create_statement" not in schema_src
      and "create_statement AS sql" not in schema_src,
      "a query still selects information_schema.create_statement")

print()
if failures:
    print(f"FAILED: {len(failures)} check(s)")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("ALL DB BINDING CHECKS PASSED")
"""Guard the local dashboard's HTTP hardening in WebServer.java.

The local dashboard is a separate server from the cloud backend, so the cloud
CSP/authentication work does not cover it. Three of these are silent
regressions that compile fine and only matter at runtime:

  * binding 0.0.0.0 instead of the configured host, which exposed the dashboard
    and its cleartext session token on every interface of what is usually a
    public Minecraft host - while config.yml advertised "localhost";
  * no framing/MIME/cache restrictions, so any site could iframe the dashboard
    and clickjack Buy/Sell;
  * a non-daemon request pool that was never shut down, leaving threads alive
    across a plugin reload.

No JDK is required, so this runs in the static checks like the other contracts.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SERVER = os.path.join(ROOT, "src", "main", "java", "com", "aureleconomy",
                      "web", "WebServer.java")
HANDLER = os.path.join(ROOT, "src", "main", "java", "com", "aureleconomy",
                       "web", "DashboardApiHandler.java")
SESSIONS = os.path.join(ROOT, "src", "main", "java", "com", "aureleconomy",
                        "web", "WebSessionManager.java")
CONFIG = os.path.join(ROOT, "src", "main", "resources", "config.yml")

failures = []


def check(label, ok, detail=""):
    print(f"  {'PASS' if ok else 'FAIL'}  {label}{'' if ok else '  -> ' + detail}")
    if not ok:
        failures.append(label)


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def code_only(text):
    """Strip string literals and then comments.

    Order matters: string literals have to go first, because Java string
    literals contain "//" (as in "http://localhost") and stripping comments
    first would swallow the rest of every such line.
    """
    text = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', text)
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def csp_block(text):
    """The CSP constant's own source, for assertions about the policy itself."""
    m = re.search(r"String\.join\(\"; \"(.*?)\);", text, re.S)
    return m.group(1) if m else ""


server = read(SERVER)
server_code = code_only(server)
handler = read(HANDLER)
handler_code = code_only(handler)
sessions = read(SESSIONS)

# ── the socket must follow configuration, not a hardcoded wildcard ───────────
check("no hardcoded 0.0.0.0 bind",
      'InetSocketAddress("0.0.0.0"' not in server
      and "InetSocketAddress('0.0.0.0'" not in server,
      "the server still binds every interface directly")
check("bind address comes from web.local.host",
      'getString("web.local.host"' in server,
      "config key is not read where the socket is created")
check("InetSocketAddress is built from the configured host",
      re.search(r"InetSocketAddress\(\s*bindHost\s*,", server_code) is not None,
      "the configured host is not what gets bound")

# A wildcard is still allowed, but only with an explicit warning, because it is
# a legitimate choice behind a TLS proxy.
# ComponentLogger spells it warn, not warning - the other one is java.util.logging
# via getLogger(), and using the wrong one does not compile.
check("a wildcard bind warns about cleartext tokens",
      re.search(r'"0\.0\.0\.0"\.equals\(bindHost\)', server) is not None
      and re.search(r"getComponentLogger\(\)\.warn\(", server) is not None,
      "binding 0.0.0.0 no longer warns via ComponentLogger.warn")

# ── response hardening ──────────────────────────────────────────────────────
for header in ("Content-Security-Policy", "X-Content-Type-Options",
               "X-Frame-Options", "Cache-Control"):
    check(f"static responses set {header}", f'"{header}"' in server)

csp = csp_block(server)
check("CSP forbids framing (clickjacking on Buy/Sell)",
      "frame-ancestors 'self'" in csp, "no frame-ancestors in the CSP")
check("CSP needs no unsafe-inline or hashes",
      csp != "" and "unsafe-inline" not in csp and "sha256" not in csp,
      "the local CSP would need an escape hatch")
check("CSP allows only self for scripts",
      "script-src 'self'" in csp, "script-src is not restricted to self")

# API responses carry balances, so they must not be cacheable.
check("API responses are no-store",
      '"Cache-Control", "no-store"' in handler,
      "player balances may be written to a cache")

# Every response that carries content must go through the header helper. Checking
# each sendResponseHeaders individually beats counting calls, because a count can
# still be satisfied while one path is left bare.
uncovered = []
for m in re.finditer(r"sendResponseHeaders\(\s*(?:200|404)", server_code):
    window = server_code[max(0, m.start() - 400):m.start()]
    if "applySecurityHeaders(exchange)" not in window:
        uncovered.append(m.start())
check("every 200/404 response applies the headers",
      not uncovered,
      f"{len(uncovered)} response path(s) skip applySecurityHeaders")

# ── shutdown ────────────────────────────────────────────────────────────────
check("request pool is held in a field", "ExecutorService httpExecutor" in server_code)
check("request pool is shut down on stop",
      "httpExecutor.shutdownNow()" in server_code,
      "non-daemon threads would outlive plugin disable")
stop_body = re.search(r"public void stop\(\)\s*\{(.*?)\n    \}", server_code, re.S)
check("shutdown happens inside stop()",
      stop_body is not None and "shutdownNow" in stop_body.group(1),
      "the pool is never shut down when the server stops")

# ── logout ──────────────────────────────────────────────────────────────────
check("a logout route exists", 'case "logout"' in handler)
check("logout revokes the presented token",
      "invalidateToken(" in handler_code,
      "logout does not revoke anything")
check("logout only accepts POST",
      re.search(r'case "logout"\s*->\s*\{(.*?)405', handler, re.S) is not None,
      "logout would be reachable by GET")
check("WebSessionManager can revoke by token",
      "public boolean invalidateToken(String token)" in sessions)
check("revoking by token does not orphan the player index",
      re.search(r"playerTokens\.remove\([^;]*,\s*token\s*\)", sessions) is not None,
      "a concurrent /web session could be left dangling")

# ── config ──────────────────────────────────────────────────────────────────
check("config.yml still defaults the host to localhost",
      re.search(r'host:\s*"localhost"', read(CONFIG)) is not None)

# ── logger API ──────────────────────────────────────────────────────────────
# Paper's ComponentLogger has no warning(String); that spelling belongs to
# java.util.logging via getLogger(). A wrong method name is a compile error on
# all three JDK variants, and this caught one during review.
ALLOWED = {"info", "warn", "error", "severe"}
bad = set()
for path in (SERVER, HANDLER, SESSIONS):
    for m in re.finditer(r"getComponentLogger\(\)\.(\w+)\s*\(", read(path)):
        if m.group(1) not in ALLOWED:
            bad.add(f"{os.path.basename(path)}: {m.group(1)}")
check("only real ComponentLogger methods are used", not bad, ", ".join(sorted(bad)))

print()
if failures:
    print(f"FAILED: {len(failures)} check(s)")
    for f in failures:
        print(f"  - {f}")
    sys.exit(1)
print("ALL LOCAL WEB HARDENING CHECKS PASSED")
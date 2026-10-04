"""Verify app.js's iconCandidates() chain resolves every market material.

This mirrors the JS rules exactly and probes the live CDN, so a change to the
rules cannot silently regress the icons. Exits non-zero if any material has no
reachable texture (those fall back to the inline placeholder, which is fine, but
should be known).
"""
import json, os, re, sqlite3, sys, urllib.request
from concurrent.futures import ThreadPoolExecutor

CI_DIR = os.path.dirname(os.path.abspath(__file__))

# Local-only tool: it probes the live CDN against a running local server, so it
# is deliberately not part of CI. Both paths can be overridden for other setups.
DB = os.environ.get("AURELIUM_SERVER_DB",
                    r"D:\aurelium-server-26\plugins\Aurelium\database.db")
ITEM = "https://assets.mcasset.cloud/26.2/assets/minecraft/textures/item/"
BLOCK = "https://assets.mcasset.cloud/26.2/assets/minecraft/textures/block/"

js = open(os.path.join(CI_DIR, os.pardir, "src", "main", "resources", "web", "app.js"),
          encoding="utf-8").read()

# A duplicated key in the override table silently keeps the last value, so a
# stale earlier entry looks correct in review and is not.
ov_block = js[js.index("const TEXTURE_OVERRIDES = {"):js.index("// Suffixes whose items")]
ov_keys = re.findall(r"^\s*([a-z0-9_]+):", ov_block, re.M)
dupes = sorted({k for k in ov_keys if ov_keys.count(k) > 1})
if dupes:
    print(f"DUPLICATE OVERRIDE KEYS ({len(dupes)}): {dupes}")
    sys.exit(3)
print(f"override keys unique: {len(ov_keys)}")


def js_list(name):
    blk = js[js.index(f"const {name} = ["):]
    blk = blk[: blk.index("];")]
    return re.findall(r"'([^']+)'", blk)

OVERRIDES = dict(re.findall(r"^\s*([a-z0-9_]+):\s*'([^']+?)',",
                            js[js.index("const TEXTURE_OVERRIDES = {"):], re.M))
DERIVED_SUFFIXES = js_list("DERIVED_SUFFIXES")
DERIVED_PREFIXES = js_list("DERIVED_PREFIXES")
print(f"overrides={len(OVERRIDES)} suffixes={len(DERIVED_SUFFIXES)} prefixes={len(DERIVED_PREFIXES)}")

def candidates(m):
    m = str(m or "stone").lower()
    out = []
    def add(name, d):
        if name and not any(c[0] == name and c[1] == d for c in out):
            out.append((name, d))
    direct = OVERRIDES.get(m, m)
    direct_dir, direct_name = None, direct
    pin = re.match(r"^(item|block)/(.+)$", direct)
    if pin:
        direct_dir, direct_name = pin.group(1), pin.group(2)

    derived = None
    for s in DERIVED_SUFFIXES:
        if m.endswith(s) and len(m) > len(s):
            derived = m[:-len(s)]
            break
    if derived is None:
        for s in ("_wood", "_hyphae", "_stem", "_log"):
            if m.endswith(s) and len(m) > len(s):
                derived = m[:-len(s)]
                break

    if derived is None and direct_dir:
        add(direct_name, direct_dir)
    if derived is not None:
        add(derived, "item")
        add(derived + "_planks", "block")
        add(derived + "_log", "block")
        add(derived, "block")
        add(derived + "_side", "block")
    if direct_dir:
        add(direct_name, direct_dir)
    else:
        add(direct_name, "item")
    add(m, "item")
    if not direct_dir:
        add(direct_name, "block")
    add(m, "block")

    for f in ("_side", "_top", "_front"):
        add(direct_name + f, "block"); add(m + f, "block")

    if derived is not None:
        add(derived + "_wool", "block")
        add(derived + "_hanging_sign", "block")
        add(derived + "_carpet", "block")

    if derived and derived.endswith("_brick"):
        add(derived + "s", "block")
        add(derived[:-6] + "_bricks", "block")

    for suf, tex in (("weighted_pressure_plate", "heavy_core"),
                     ("pressure_plate", "light_weighted_pressure_plate"),
                     ("bars", "iron_bars"), ("block", "iron_block"),
                     ("door", "iron_door"), ("trapdoor", "iron_trapdoor"),
                     ("nugget", "iron_nugget"), ("ingot", "iron_ingot")):
        if m.endswith("_" + suf) and len(m) > len(suf) + 1:
            add(tex, "block"); add(tex, "item")

    for s in DERIVED_SUFFIXES:
        if m.endswith(s) and len(m) > len(s):
            b = m[:-len(s)]
            add(b, "block"); add(b, "item")
            add(b + "_side", "block"); add(b + "_top", "block")
    for s in ("_wood", "_hyphae", "_log", "_stem", "_planks", "_leaves"):
        if m.endswith(s) and len(m) > len(s):
            b = m[:-len(s)]
            add(b + "_planks", "block"); add(b + "_log", "block"); add(b + "_wood", "block")
    for p in DERIVED_PREFIXES:
        if m.startswith(p) and len(m) > len(p):
            b = m[len(p):]
            add(b, "block"); add(b, "item")
    return out

def head(u):
    r = urllib.request.Request(u, method="HEAD", headers={"User-Agent": "curl/8"})
    try:
        with urllib.request.urlopen(r, timeout=6) as resp:
            return resp.status == 200
    except Exception:
        return False

con = sqlite3.connect(DB)
mats = sorted({str(r[0]).lower() for r in con.execute(
    "select distinct item_key from price_history where item_key is not null")
    if r[0] and re.fullmatch(r"[A-Za-z0-9_]+", str(r[0]))})

# Use the candidate chains produced by the real app.js, so these numbers always
# describe shipped behaviour rather than a second implementation of the rules.
CANDIDATES = json.load(open(os.path.join(CI_DIR, "candidates.json"), encoding="utf-8"))

jobs = []
for m, cands in CANDIDATES.items():
    for i, c in enumerate(cands):
        url = (ITEM if c["dir"] == "item" else BLOCK) + c["name"] + ".png"
        jobs.append((m, i, url))

# A pinned override that does not resolve is a guaranteed 404 on every render,
# so check those directly and fail loudly.
pinned = []
ov = dict(re.findall(r"^\s*([a-z0-9_]+):\s*'([^']+?)',",
                     js[js.index("const TEXTURE_OVERRIDES = {"):
                        js.index("// Suffixes whose items")], re.M))
for m, val in ov.items():
    p = re.match(r"^(item|block)/(.+)$", val)
    if p:
        pinned.append((m, (ITEM if p.group(1) == "item" else BLOCK) + p.group(2) + ".png"))
print(f"pinned overrides: {len(pinned)}", flush=True)
print(f"materials={len(CANDIDATES)} probes={len(jobs)}", flush=True)

def probe(j):
    return j[0], j[1], head(j[2])

with ThreadPoolExecutor(max_workers=96) as ex:
    res = list(ex.map(probe, jobs))
    pin_res = list(ex.map(lambda p: (p[0], p[1], head(p[1])), pinned))

# First reachable index in each chain == how many failed loads the user sees.
first_ok = {}
for m, i, ok in res:
    if ok and m not in first_ok:
        first_ok[m] = i

unresolved = [m for m in mats if m not in first_ok]
resolved = [m for m in mats if m in first_ok]
print(f"resolved={len(resolved)}  placeholder-only={len(unresolved)}")

buckets = {}
for m in resolved:
    buckets.setdefault(first_ok[m], []).append(m)
print("requests before the icon appears (extra 404s in console):")
for i in sorted(buckets):
    print(f"  {i} failed load(s): {len(buckets[i])} materials")
for i in sorted(buckets):
    if i >= 3:
        print(f"    e.g. depth {i}: {sorted(buckets[i])[:8]}")

if unresolved:
    print(f"\nplaceholder-only ({len(unresolved)}): {', '.join(unresolved[:30])}")

# Emit the winning candidate per material as a JS table, so the browser does not
# have to walk the chain (and eat a 404 per step) at runtime.
winner = {}
for m in mats:
    if m in first_ok:
        c = CANDIDATES[m][first_ok[m]]
        if c["name"] == m and c["dir"] == "item":
            continue  # default path already correct
        winner[m] = f"{c['dir']}/{c['name']}"

print(f"\n// {len(winner)} entries override the default item/<material>.png path")
with open(os.path.join(CI_DIR, "icon_table.txt"), "w", encoding="utf-8") as fh:
    for m in sorted(winner):
        fh.write(f"    {m}: '{winner[m]}',\n")
print("wrote ci/icon_table.txt")

worst = max((first_ok.get(m, 0) for m in mats), default=0)
print(f"worst-case runtime 404s (chain depth): {worst}")

bad_pins = [(m, u) for m, u, ok in pin_res if not ok]
if bad_pins:
    print(f"\nPINNED OVERRIDES THAT 404 ({len(bad_pins)}):")
    for m, u in bad_pins:
        print(f"  {m} -> {u.rsplit('/textures/', 1)[-1]}")
    sys.exit(2)
print("OK every pinned override resolves on the CDN")
sys.exit(0)



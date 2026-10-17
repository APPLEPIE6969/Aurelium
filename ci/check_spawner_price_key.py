#!/usr/bin/env python3
"""Guard the spawner price key.

MarketManager stores a spawner's price under a composite key built from the
material and the display name, e.g. "SPAWNER_IRON_GOLEM_SPAWNER", because every
spawner is Material.SPAWNER and the material alone cannot tell them apart. Reads
used to pass the raw display name, "Iron Golem Spawner", which is not a key that
exists, so every lookup missed and getBuyPrice returned zero. That left all 52
spawners unbuyable, worth nothing on the dashboard, and recorded into the price
history chart as a flat line at zero.

Both paths now go through MarketManager.entryKey(). This checks that:

  * entryKey exists and builds the same key processEntry used to build inline,
  * no price or currency lookup is passed entry.customName any more, which is
    the exact shape of the original bug,
  * the call sites that read spawner prices use entryKey.

The check is on source text rather than behaviour because MarketManager needs a
live plugin instance and an entry cache to be constructed, and the bug was a
textual mismatch between two places that build the same key.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java" / "com" / "aureleconomy"

MM = SRC / "market" / "MarketManager.java"

failures = []


def ok(msg):
    print(f"PASS: {msg}")


def bad(msg):
    print(f"FAIL: {msg}")
    failures.append(msg)


def main():
    text = MM.read_text(encoding="utf-8")

    # 1. The helper exists, in both overloads.
    if re.search(r"public\s+String\s+entryKey\s*\(\s*MarketEntry\s+entry\s*\)", text):
        ok("MarketManager exposes entryKey(MarketEntry)")
    else:
        bad("MarketManager is missing entryKey(MarketEntry)")

    if re.search(r"public\s+String\s+entryKey\s*\(\s*Material\s+material\s*,\s*String\s+customName\s*\)", text):
        ok("MarketManager exposes entryKey(Material, String)")
    else:
        bad("MarketManager is missing entryKey(Material, String)")

    # 2. The helper builds the composite key: material + "_" + uppercased,
    #    underscored custom name. This is the exact format the config uses.
    # Read up to the method's closing brace at the method's own indentation. A
    # non-greedy match to the first "}" would stop at the null-check's brace and
    # miss the return that actually builds the key.
    start = re.search(
        r"public\s+String\s+entryKey\s*\(\s*Material\s+material\s*,\s*String\s+customName\s*\)\s*\{",
        text)
    if not start:
        bad("could not find entryKey(Material, String)")
        b = ""
    else:
        indent = len(text[: start.start()].split("\n")[-1])
        rest = text[start.end():]
        lines = []
        for ln in rest.split("\n"):
            if ln.strip() == "}" and (len(ln) - len(ln.lstrip())) == indent:
                break
            lines.append(ln)
        b = "\n".join(lines)
        if 'material.name() + "_"' in b and 'customName.replace(" ", "_").toUpperCase()' in b:
            ok("entryKey builds MATERIAL_CUSTOMNAME, matching the config key format")
        else:
            bad("entryKey does not build MATERIAL_CUSTOMNAME as the config expects")

        # A null custom name must fall back to the plain material key, since that
        # is how every non-spawner item is stored.
        if re.search(r"if\s*\(\s*customName\s*==\s*null\s*\)\s*\{?\s*return\s+material\.name\(\)", b):
            ok("entryKey falls back to the material key when there is no custom name")
        else:
            bad("entryKey does not fall back to the material key for plain items")

    # 3. processEntry writes through the shared helper, not its own inline copy.
    proc = re.search(r"private\s+void\s+processEntry.*?\n\s*entryCache\.put", text, re.S)
    if proc and "entryKey(entry)" in proc.group(0):
        ok("processEntry stores prices under entryKey(entry)")
    else:
        bad("processEntry does not use entryKey, so writes and reads can drift again")

    # 4. The original bug: any lookup handed entry.customName.
    java = sorted(SRC.rglob("*.java"))
    offenders = []
    for path in java:
        for m in re.finditer(
                r"get(?:Buy|Sell)?(?:Price|Currency)\(\s*entry\.customName\s*\)", path.read_text(encoding="utf-8")):
            offenders.append(f"{path.relative_to(ROOT)}:{path.read_text(encoding='utf-8')[:m.start()].count(chr(10)) + 1}")
    if offenders:
        bad("price/currency lookups still passed entry.customName: " + ", ".join(offenders))
    else:
        ok("no price or currency lookup is passed entry.customName anywhere")

    # 4b. A key rebuilt by hand from customName. The first fix missed four such
    # sites in WebSnapshot and CloudSyncManager and three more in the GUIs, all of
    # which read back as zero for the 205 entries that have a custom name. This is
    # the shape to catch, so it is checked directly rather than by behaviour.
    handbuilt = []
    for path in java:
        text = path.read_text(encoding="utf-8")
        for m in re.finditer(r'String\s+\w*[Kk]ey\s*=\s*[^;]*?customName[^;]*?;', text, re.S):
            # entryKey's own body is the one legitimate place this appears.
            line_no = text[:m.start()].count(chr(10)) + 1
            rel = f"{path.relative_to(ROOT)}:{line_no}"
            if path.name == "MarketManager.java" and 275 <= line_no <= 305:
                continue
            handbuilt.append(rel)
    if handbuilt:
        bad("price keys still built by hand from customName, use entryKey: "
            + ", ".join(handbuilt))
    else:
        ok("no price key is built by hand from customName outside entryKey")

    # 4c. Trade recording is keyed too. A spawner purchase recorded under the
    # display name lands in trade_volume and last-sold-price under a key nothing
    # reads back, so volume silently undercounts every named item.
    for rel in ("gui/MarketGUI.java", "gui/ShopGUI.java"):
        p = SRC / rel
        t = p.read_text(encoding="utf-8")
        bad_txn = [ln for ln in t.splitlines()
                   if "onTransaction(" in ln and "entry.customName" in ln]
        if bad_txn:
            bad(f"{rel} records a transaction under entry.customName")
        else:
            ok(f"{rel} records transactions under the entryKey")

    # 4d. A web purchase arrives as a key and has to be turned back into an item.
    # Material.valueOf cannot parse a composite key, so the material has to come
    # from the entry. Defaulting it to SPAWNER hands a player a raw spawner block
    # when they buy an enchanted book, which is exactly the mistake that made 205
    # items look correct in-game and wrong on the web.
    cm = SRC / "web" / "CloudSyncManager.java"
    text = cm.read_text(encoding="utf-8")
    start = text.find("private void executeMarketWebBuy(")
    if start < 0:
        bad("could not find executeMarketWebBuy")
    else:
        end = text.find("private ", start + 10)
        body = text[start:end if end > 0 else len(text)]
        if "Material.SPAWNER" in body:
            bad("executeMarketWebBuy falls back to Material.SPAWNER, which delivers "
                "a spawner block for any named item")
        else:
            ok("executeMarketWebBuy resolves the material from the entry, not a guess")
        if "getEntryCache().get(itemKey)" in body:
            ok("executeMarketWebBuy resolves a named item through the entry cache")
        else:
            bad("executeMarketWebBuy does not resolve a named item through the entry cache")

    # 5. The files that display spawner prices must go through entryKey.
    for rel in ("gui/MarketGUI.java", "gui/ShopGUI.java", "webstore/WebSnapshot.java"):
        p = SRC / rel
        t = p.read_text(encoding="utf-8")
        if "entryKey(" in t:
            ok("%s reads spawner prices through entryKey" % rel)
        else:
            bad("%s does not use entryKey" % rel)

    print()
    if failures:
        print("%d check(s) failed" % len(failures))
        sys.exit(1)
    print("SPAWNER PRICE KEY: ALL CHECKS PASS")


if __name__ == "__main__":
    main()
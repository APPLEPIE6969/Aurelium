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


def strip_comments(text):
    """Drop // and /* */ comments.

    A textual guard otherwise trips over its own documentation. The comment
    explaining why a Material.SPAWNER fallback was removed still contains the
    string Material.SPAWNER, which is exactly the token being guarded against.
    String literals are stepped over so a "//" inside one is not read as a
    comment.
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        two = text[i:i + 2]
        if two == "//":
            j = text.find(chr(10), i)
            i = n if j < 0 else j
        elif two == "/*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif text[i] in chr(34) + chr(39):
            quote = text[i]
            j = i + 1
            while j < n:
                if text[j] == chr(92):
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


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
    #
    # A named item may be resolved by reading the entry cache inline or by calling
    # MarketItemFactory.entryForKey, which reads the same cache. Both are accepted,
    # and the factory is checked for itself below, so routing through it cannot
    # become a way to skip the cache rather than share it.
    for rel, signature in (
        ("web/CloudSyncManager.java", "private void executeMarketWebBuy("),
        ("web/WebPurchaseExecutor.java", "private boolean buy("),
        ("web/WebPurchaseExecutor.java", "private boolean sell("),
    ):
        p = SRC / rel
        text = p.read_text(encoding="utf-8")
        where = signature.split("(")[0].split()[-1]
        start = text.find(signature)
        if start < 0:
            bad("could not find %s in %s" % (where, rel))
            continue
        end = text.find("private ", start + 10)
        body = strip_comments(text[start:end if end > 0 else len(text)])
        if "Material.SPAWNER" in body:
            bad("%s falls back to Material.SPAWNER, which delivers a spawner block "
                "for any named item" % where)
        else:
            ok("%s resolves the material from the entry, not a guess" % where)
        if "getEntryCache().get(itemKey)" in body or "MarketItemFactory.entryForKey(" in body:
            ok("%s resolves a named item through the entry cache" % where)
        else:
            bad("%s does not resolve a named item through the entry cache" % where)

    # 4e. The factory has to actually read the cache, or the call sites above are
    # only passing it by name.
    mif = SRC / "market" / "MarketItemFactory.java"
    if not mif.exists():
        bad("could not find MarketItemFactory.java")
    else:
        factory = strip_comments(mif.read_text(encoding="utf-8"))
        if "getEntryCache()" in factory:
            ok("MarketItemFactory.entryForKey reads the entry cache")
        else:
            bad("MarketItemFactory.entryForKey does not read the entry cache, so the "
                "call sites routed through it resolve a key by guessing")

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
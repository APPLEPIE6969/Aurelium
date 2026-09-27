"""Report classes used in index.html (and emitted by app.js) that have no CSS rule.

The +/- buttons were invisible because the markup used .qty-btn while the
stylesheet only defined .amount-btn. This finds every other instance of that
class of bug, plus elements styled by id that the markup never uses.
"""
import re
import sys

BASE = r"D:\Aurelium-plugin\src\main\resources\web"
html = open(BASE + r"\index.html", encoding="utf-8").read()
css = open(BASE + r"\style.css", encoding="utf-8").read()
js = open(BASE + r"\app.js", encoding="utf-8").read()

css_classes = set(re.findall(r"\.([a-zA-Z][\w-]*)", css))
css_ids = set(re.findall(r"#([a-zA-Z][\w-]*)", css))
# Drop hex colours (#e8e8ec), which look like id selectors but are not.
css_ids = {i for i in css_ids if not re.fullmatch(r"[0-9a-fA-F]{3,8}", i)}

html_classes = set()
for m in re.findall(r'class="([^"]+)"', html):
    html_classes |= {c for c in m.split() if c and "$" not in c}
html_ids = set(re.findall(r'id="([^"]+)"', html))

js_classes = set()
for m in re.findall(r'class="([^"$]+)"', js):
    js_classes |= {c for c in m.split() if c}
for m in re.findall(r"className = '([^']+)'", js):
    js_classes |= {c for c in m.split() if c}
for m in re.findall(r'class="([a-z0-9 _-]+)"\s*\+', js):
    js_classes |= {c for c in m.split() if c}
for a, b in re.findall(r"\?\s*'([a-z-]+)'\s*:\s*'([a-z-]+)'", js):
    js_classes |= {x for x in (a, b) if x}

# Utilities and state classes legitimately have no rule of their own.
IGNORE = {
    "hidden", "active", "closing", "loading", "disabled", "selected", "open",
    "false", "true", "up", "down", "neutral", "bid", "bin", "expiring",
    "sidebar-item", "page-content",
    # Sort state words: these reach the DOM as data-dir / aria-sort values and
    # as ternary results, not as class names.
    "asc", "desc", "ascending", "descending",
}

missing = sorted((html_classes | js_classes) - css_classes - IGNORE)
print(f"markup classes: {len(html_classes)}   js classes: {len(js_classes)}   css rules: {len(css_classes)}")
if missing:
    print(f"\nUNSTYLED classes ({len(missing)}):")
    for c in missing:
        where = []
        if c in html_classes:
            where.append("html")
        if c in js_classes:
            where.append("js")
        print(f"  .{c:<28} used in {'+'.join(where)}")
else:
    print("\nOK every class has a css rule")

dead_ids = sorted(css_ids - html_ids - set(re.findall(r'id="([^"]+)"', js)))
if dead_ids:
    print(f"\nCSS targets ids the markup never uses ({len(dead_ids)}): {dead_ids}")

sys.exit(1 if missing else 0)

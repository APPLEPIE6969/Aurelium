"""Static check: does app.js only touch ids/classes that index.html + style.css define?

This is the exact failure that shipped in WebMarketMC (app.js from one layout,
index.html from another), so it is worth checking mechanically.
"""
import re
import sys

HTML = r"D:\Aurelium-plugin\src\main\resources\web\index.html"
CSS = r"D:\Aurelium-plugin\src\main\resources\web\style.css"
JS = r"D:\Aurelium-plugin\src\main\resources\web\app.js"

html = open(HTML, encoding="utf-8").read()
css = open(CSS, encoding="utf-8").read()
js = open(JS, encoding="utf-8").read()

# ids defined in markup
html_ids = set(re.findall(r'id="([^"]+)"', html))

# ids the JS reaches for
js_ids = set(re.findall(r"getElementById\('([^']+)'\)", js))
# ids the JS writes via template literals
js_ids |= set(re.findall(r"getElementById\(`([^`$]+)`\)", js))
# ids created in template literals like id="pg-prev"
js_ids |= set(re.findall(r'id="([a-z0-9-]+)"', js))
# ids the JS creates in its own template literals (e.g. id="pg-prev") are valid
js_created = set(re.findall(r'id="([a-z0-9-]+)"', js))

# ids the inline HTML handlers reference (closeModal('x'), data-page=...)
handler_ids = set(re.findall(r"closeModal\('([^']+)'\)", html))
handler_pages = set(re.findall(r'data-page="([^"]+)"', html))
page_ids = {f"page-{p}" for p in handler_pages}

# globals the inline handlers require (from both the markup and js templates)
globals_needed = set()
for src in (html, js):
    globals_needed |= set(re.findall(r'on(?:click|input|error)="([a-zA-Z_$][\w$]*)\(', src))
    globals_needed |= set(re.findall(r'on(?:click|input|error)="([a-zA-Z_$][\w$]*)\b', src))
globals_needed.discard("closeModal")

# classes defined in CSS
css_classes = set(re.findall(r'\.([a-zA-Z][\w-]*)', css))
# classes the JS emits in markup (only literal tokens, no template fragments)
js_classes = set()
for m in re.findall(r'class="([^"$]+)"', js):
    js_classes |= {c for c in m.split() if c}
for m in re.findall(r"className = '([^']+)'", js):
    js_classes |= {c for c in m.split() if c and '$' not in c}
for m in re.findall(r'class="([a-z0-9 _-]+)"\s*\+', js):
    js_classes |= {c for c in m.split() if c}
# classes interpolated via ${...} ternaries, e.g. 'active' / 'bin' / 'bid'
for m in re.findall(r"\?\s*'([a-z-]+)'\s*:\s*'([a-z-]+)'", js):
    js_classes |= {x for x in m if x}

fail = False

# Exactly one page may be visible on first paint. switchPage() only runs once
# the session check succeeds, so any extra un-hidden page renders underneath the
# real one -- and because the sidebars are position:fixed they overlap.
pages = re.findall(r'class="page-content([^"]*)"\s+id="page-([a-z]+)"', html)
visible = [name for cls, name in pages if "hidden" not in cls]
if len(pages) != 4:
    fail = True
    print(f"  EXPECTED 4 PAGES, found {len(pages)}: {[p[1] for p in pages]}")
elif len(visible) != 1:
    fail = True
    print(f"  {len(visible)} PAGES VISIBLE ON LOAD: {visible}")
else:
    print(f"  OK exactly one page visible on load ({visible[0]})")

missing_ids = sorted(i for i in js_ids if i not in html_ids and i not in js_created)
print(f"html defines {len(html_ids)} ids")
print(f"js references {len(js_ids)} ids ({len(js_created)} created by js)")
if missing_ids:
    fail = True
    print("  MISSING IDS:", ", ".join(missing_ids))
else:
    print("  OK every id the js uses exists in index.html")

bad_handlers = sorted(i for i in handler_ids if i not in html_ids)
if bad_handlers:
    fail = True
    print("  inline handlers target missing ids:", ", ".join(bad_handlers))
else:
    print("  OK all closeModal() targets exist")

missing_pages = sorted(p for p in page_ids if p not in html_ids)
if missing_pages:
    fail = True
    print("  MISSING PAGE CONTAINERS:", ", ".join(missing_pages))
else:
    print("  OK all nav tabs have a page container")

js_globals = set(re.findall(r'window\.([A-Za-z_$][\w$]*)\s*=', js))
missing_globals = sorted(g for g in globals_needed if g not in js_globals)
if missing_globals:
    fail = True
    print("  INLINE HANDLERS WITH NO GLOBAL:", ", ".join(missing_globals))
else:
    print(f"  OK all {len(globals_needed)} inline-handler globals are defined")

undefined_classes = sorted(c for c in js_classes if c not in css_classes)
if undefined_classes:
    print("  note: classes emitted by js with no css rule:", ", ".join(undefined_classes))
else:
    print("  OK every class the js emits has a css rule")

# Regression guard: JSON.stringify() interpolated into a quoted attribute. The
# JSON's own double quotes terminate the attribute and the handler dies with
# "Unexpected end of input". Payload data must travel in data-* instead.
attr_json = re.findall(r'on\w+="[^"]*\$\{[^}]*JSON\.stringify', js)
if attr_json:
    fail = True
    print("  JSON INSIDE AN HTML ATTRIBUTE:", len(attr_json), "site(s)")
    print("    (use data-obj=\"...\" + a delegated listener instead)")
else:
    print("  OK no JSON embedded in onclick/onerror attributes")

# data-obj payloads must be HTML-escaped, since they carry arbitrary names.
raw_obj = re.findall(r'data-obj="\$\{(?!esc\()', js)
if raw_obj:
    fail = True
    print("  UNESCAPED data-obj PAYLOAD:", len(raw_obj), "site(s)")
else:
    print("  OK every data-obj payload is HTML-escaped")

# A renderer that appends without first emptying the container stacks a copy of
# itself on every re-render (the sidebar showed 10x duplicates before this).
# A container whose children remove themselves is fine, so only flag the rest.
for fn in re.findall(r"function\s+(\w+)\s*\([^)]*\)\s*\{(.*?)\n\}", js, re.S):
    name, body = fn
    if "appendChild" not in body:
        continue
    if "replaceChildren" in body or "innerHTML = ''" in body:
        continue
    if re.search(r"setTimeout\(\s*\(\)\s*=>\s*\w+\.remove\(\)", body):
        continue  # self-cleaning, e.g. toasts
    print(f"  note: {name}() appends without clearing its container")

# unused ids in markup (informational)
unused = sorted(i for i in html_ids if i not in js_ids and i not in handler_ids
                and i not in page_ids and not i.startswith(('nav-', 'amount-', 'order-fill-amount-')))
if unused:
    print("  note: ids in markup the js never touches:", ", ".join(unused))

print("RESULT:", "FAIL" if fail else "PASS")
sys.exit(1 if fail else 0)

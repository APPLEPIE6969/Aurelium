#!/usr/bin/env python3
"""Regression checks for the auction item-loss and custom-item config fixes.

These cover control flow that cannot be unit tested without a live server:
constructing an ItemStack needs a running server's item registry, so the JUnit
suite can only reach the pure helpers. These assertions lock in the specific
mistakes that caused the two bug reports, so the fixes cannot be silently
reverted by a later refactor.

Issue #33 - listing an item at the auction house destroyed it whenever the
database insert failed, because the hand was cleared before the result was
known and nothing was restored on the failure path.

Issue #34 - /customitems scan reported "0 items" with no explanation, an explicit
`excluded-namespaces: []` was overwritten with the defaults, hand-written
`discovered-items` entries were dropped in silence, and /customitems reload never
applied config at all.
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

failures = []
checks = 0


def read(rel):
    with io.open(os.path.join(ROOT, rel), encoding='utf-8') as fh:
        return fh.read()


def check(name, condition, detail=''):
    global checks
    checks += 1
    if condition:
        print(f'PASS: {name}')
    else:
        print(f'FAIL: {name}' + (f' - {detail}' if detail else ''))
        failures.append(name)


def strip_comments(src):
    """Remove // line comments so commented-out code cannot satisfy a check."""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return '\n'.join(line.split('//')[0] for line in src.split('\n'))


# --------------------------------------------------------------------------
# Issue #33: the item must only be taken once the listing is durable
# --------------------------------------------------------------------------

auction_cmd = strip_comments(read('src/main/java/com/aureleconomy/commands/AuctionCommand.java'))
auction_gui = strip_comments(read('src/main/java/com/aureleconomy/gui/AuctionGUI.java'))
auction_mgr = strip_comments(read('src/main/java/com/aureleconomy/auction/AuctionManager.java'))

for label, src in (('AuctionCommand', auction_cmd), ('AuctionGUI', auction_gui)):
    check(
        f'#33 {label} does not clear the main hand directly',
        'setItemInMainHand(' not in src,
        'clearing the hand must go through InventoryUtils.clearMainHandIfSimilar '
        'so an unrelated stack is never destroyed',
    )
    check(
        f'#33 {label} clears the hand via the guarded helper',
        'clearMainHandIfSimilar(' in src,
    )
    check(
        f'#33 {label} passes a completion callback to listAuction',
        'purchaseMode, (' in src or 'mode, (' in src,
        'without a callback there is no point at which success is known',
    )
    check(
        f'#33 {label} refunds the listing fee on failure',
        '.deposit(player' in src,
        'the fee is withdrawn before the insert, so failure must refund it',
    )
    check(
        f'#33 {label} tells the player the item was not taken',
        'was not taken' in src,
    )

# The hand must only be cleared on the success branch of the callback.
for label, src in (('AuctionCommand', auction_cmd), ('AuctionGUI', auction_gui)):
    idx_null = src.find('listed == null')
    idx_clear = src.find('clearMainHandIfSimilar(')
    check(
        f'#33 {label} clears the hand only after the failure branch is handled',
        idx_null != -1 and idx_clear != -1 and idx_clear > idx_null,
        'clearMainHandIfSimilar must appear after the `listed == null` check, '
        'otherwise the item is taken even when the insert failed',
    )

check(
    '#33 AuctionManager.listAuction holds the shared write lock for the insert',
    re.search(r'synchronized\s*\(\s*plugin\.getDatabaseManager\(\)\.getWriteLock\(\)\s*\)', auction_mgr) is not None,
    'EconomyManager and CustomItemRegistry already synchronize on this monitor; '
    'without it a concurrent balance write can make the INSERT throw',
)

# There are two overloads: a short one that delegates, and the real one taking
# the completion callback. Slice out the callback overload specifically, using
# brace matching so nested blocks do not truncate it.
def method_body(src, start):
    i = src.find('{', start)
    if i == -1:
        return ''
    depth = 0
    for j in range(i, len(src)):
        if src[j] == '{':
            depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
    return src[i:]


_overloads = [m.start() for m in re.finditer(r'public void listAuction\(', auction_mgr)]
list_auction = method_body(auction_mgr, _overloads[1]) if len(_overloads) >= 2 else ''

check(
    '#33 AuctionManager.listAuction catches more than SQLException',
    'catch (SQLException e)' not in list_auction and re.search(r'catch\s*\(\s*Exception\s+\w+\s*\)', list_auction) is not None,
    'getConnection() can return null after a failed reconnect, which throws '
    'NullPointerException and would skip the failure callback entirely',
)

check(
    '#33 AuctionManager.listAuction always reports a result to the caller',
    'runOnMainThread(created, onListed)' in list_auction,
)

check(
    '#33 AuctionManager.listAuction reports failure as a null listing',
    'created = null;' in list_auction,
)

# purchaseUnits used to call getWriteLock() without synchronizing, locking nothing.
check(
    '#33 AuctionManager.purchaseUnits synchronizes on the write lock',
    re.search(
        r'synchronized\s*\(\s*plugin\.getDatabaseManager\(\)\.getWriteLock\(\)\s*\)\s*\{\s*try \(PreparedStatement ps = plugin\.getDatabaseManager\(\)\.getConnection\(\)\s*\.prepareStatement\("UPDATE auctions SET item_data',
        auction_mgr,
    ) is not None,
    'a bare getWriteLock() call returns the monitor without locking it',
)
check(
    '#33 AuctionManager no longer notifies a lock it never held',
    'getWriteLock().notifyAll()' not in auction_mgr,
)

# --------------------------------------------------------------------------
# Issue #34: custom items config and scanner diagnostics
# --------------------------------------------------------------------------

registry = strip_comments(read('src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java'))
scanner = strip_comments(read('src/main/java/com/aureleconomy/scanner/UnifiedItemScanner.java'))
cmd = strip_comments(read('src/main/java/com/aureleconomy/commands/CustomItemsCommand.java'))
config = read('src/main/resources/config.yml')

overrides = registry[registry.find('public void loadConfigOverrides()'):]
overrides = overrides[:overrides.find('\n  /**')] if '\n  /**' in overrides else overrides

check(
    '#34 loadConfigOverrides no longer silently drops unknown entries',
    'if (!itemsById.containsKey(canonicalId)) continue;' not in overrides,
    'a hand-written entry hit this and vanished with no message',
)
check(
    '#34 loadConfigOverrides records why entries were skipped',
    'skipped.add(' in overrides and 'Skipped' in overrides,
)
check(
    '#34 loadConfigOverrides accepts a material: key to define an item',
    '".material"' in overrides,
)
check(
    '#34 loadConfigOverrides validates the material it is given',
    'Material.matchMaterial(' in overrides and 'invalid material' in overrides,
)
check(
    '#34 loadConfigOverrides publishes config-defined items to the market',
    'addToMarket(' in overrides,
)

check(
    '#34 scanner only restores namespace defaults when the key is absent',
    re.search(r'contains\("custom-items\.excluded-namespaces"\)', scanner) is not None,
    'an explicit [] must be distinguishable from a missing key',
)
check(
    '#34 scanner exposes the namespace resolution for testing',
    'static Set<String> resolveExcludedNamespaces(' in scanner,
)
check(
    '#34 scanner re-reads config on demand',
    'public void reloadSettingsFromConfig()' in scanner,
)

check(
    '#34 /customitems reload applies config overrides',
    re.search(r'handleReload[\s\S]*?loadConfigOverrides\(\)', cmd) is not None,
    'reload previously ignored every change made to config.yml',
)
check(
    '#34 /customitems reload re-reads config from disk',
    re.search(r'handleReload[\s\S]*?plugin\.reloadConfig\(\)', cmd) is not None,
)
check(
    '#34 /customitems scan applies config overrides',
    re.search(r'handleScan[\s\S]*?loadConfigOverrides\(\)', cmd) is not None,
)
check(
    '#34 /customitems scan explains a zero-item result',
    'Nothing found' in cmd,
    'issue #34 asked "am I missing something here?" - the command has to say',
)

check(
    '#34 config.yml documents the material: key',
    'material:' in config,
)
check(
    '#34 config.yml documents that /customitems reload is enough',
    '/customitems reload' in config,
)

print()
print(f'{checks - len(failures)}/{checks} checks passed')
if failures:
    print('FAILURES:')
    for f in failures:
        print(f'  - {f}')
    sys.exit(1)
print('Issue #33 / #34 regression checks: COMPLETE')
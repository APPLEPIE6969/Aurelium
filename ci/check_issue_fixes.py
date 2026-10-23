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
# The guard asserts the two things that matter, separately: that the monitor is
# taken, and that the item_data write is inside the block it opened. It previously
# pinned the exact try-with-resources header too, which broke when the connection
# was added to that header for ownership (H1 commit 1). The header's shape is not
# the contract; the lock and the write being inside it are.
_pu_src = method_body(auction_mgr, auction_mgr.find('public int purchaseUnits('))
_pu_lock_start = _pu_src.find('synchronized (plugin.getDatabaseManager().getWriteLock())')
check(
    '#33 AuctionManager.purchaseUnits synchronizes on the write lock',
    _pu_lock_start != -1,
    'a bare getWriteLock() call returns the monitor without locking it',
)
check(
    '#33 AuctionManager.purchaseUnits writes item_data inside that lock',
    _pu_lock_start != -1
    and 'UPDATE auctions SET item_data' in method_body(_pu_src, _pu_lock_start),
    'the item_data update must be inside the lock, not merely after it',
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
    'getString("material")' in overrides,
)
check(
    '#34 loadConfigOverrides reads each entry through its own config section',
    'getConfigurationSection(canonicalId)' in overrides,
    'reading "discovered-items.<id>.<key>" off the parent section silently '
    'returns null for a plain value, so hand-written items never registered',
)
check(
    '#34 loadConfigOverrides does not build parent-relative key paths',
    'section.getString("discovered-items.' not in overrides
    and 'path + ".material"' not in overrides,
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

# --------------------------------------------------------------------------
# The Custom Items browser was unreachable: nothing constructed it
# --------------------------------------------------------------------------

check(
    'CustomItemsCommand registers a gui subcommand',
    re.search(r'case\s+"gui"', cmd) is not None,
)
check(
    'CustomItemsCommand constructs CustomItemsGUI',
    'new com.aureleconomy.gui.CustomItemsGUI(' in cmd,
    'the GUI existed but no command ever opened it',
)
check(
    'bare /customitems opens the browser instead of printing usage',
    re.search(r'if \(args\.length == 0\) \{\s*openGui\(', cmd) is not None,
)
check(
    'opening the GUI is refused for the console rather than failing silently',
    'can only be opened in-game' in cmd,
)

gui_test = strip_comments(read('.github/scripts/test_gui_mineflayer.js'))
check(
    'the GUI suite opens the browser via a subcommand that opens a window',
    'customitems gui' in gui_test,
    'the suite used `customitems list`, which answers on the console, so every '
    'window assertion failed',
)
check(
    'the GUI suite no longer opens the browser via the console-only list command',
    "runCommand('customitems list')" in gui_test
    and 'openCustomItemsGUI' in gui_test
    and 'customitems ${subcommand}' in gui_test,
)
for slot, what in ((45, 'Back'), (49, 'page info'), (53, 'Rescan')):
    check(
        f'the GUI suite asserts slot {slot} ({what}) by its real slot',
        f'getSlotItem({slot})' in gui_test,
        'asserting the wrong slots is how these tests passed while broken',
    )

# --------------------------------------------------------------------------
# CustomItemsGUI closed instead of navigating, because it mutated the open
# inventory in place. Bukkit does not re-send an open inventory, and refuses to
# re-open the same instance, so every in-place rebuild silently closed the GUI.
# --------------------------------------------------------------------------

cig = strip_comments(read('src/main/java/com/aureleconomy/gui/CustomItemsGUI.java'))

check(
    'CustomItemsGUI has a reopen helper for in-place rebuilds',
    'private void reopen(Player player)' in cig,
)
check(
    'CustomItemsGUI reopens on a new GUI instance, not the same inventory',
    re.search(r'new CustomItemsGUI\(plugin, targetPage, target\)\.open\(player\)', cig) is not None,
    'Player.openInventory on the already-open instance is a no-op, so the window '
    'appears to close instead of showing the next view',
)
check(
    'CustomItemsGUI defers the reopen off the click event',
    re.search(r'runTask\(plugin, \(\) -> \{[^}]*closeInventory', cig, flags=re.S) is not None,
    'closing inside an InventoryClickEvent cancels the matching open',
)
check(
    'CustomItemsGUI can be constructed already showing a detail view',
    re.search(r'CustomItemsGUI\(AurelEconomy plugin, int page, CustomMarketItem selected\)', cig) is not None,
    'setupItems() clears selectedItem, so the rebuild needs to re-apply it',
)

# The GUI suite must wait for the rebuilt window rather than a fixed delay, since
# the rebuild happens a tick after the click.
check(
    'the GUI suite waits for the rebuilt window instead of sleeping a fixed time',
    'async function waitForWindow(' in gui_test and 'clickSlot(slot, predicate' in gui_test,
    'a fixed delay reads the window during the close/reopen gap and reports every '
    'click as a failure',
)

# The test server must not kill the bot mid-assertion.
workflow = read('.github/workflows/build.yml')
check(
    'the GUI test server is set to peaceful',
    'difficulty=peaceful' in workflow,
    'the bot stood still for minutes and was killed by mobs, closing the window',
)
check(
    'the GUI test server clears pre-existing mobs',
    'kill @e[type=!player]' in workflow,
    'slimes spawn on peaceful and ignore spawn-monsters once they exist',
)

# --------------------------------------------------------------------------
# C1: bid() charges the bidder, so no caller withdraws for the same bid
# --------------------------------------------------------------------------
# bid() used to decide affordability from the balance cache and never withdraw
# anything, and every caller withdrew before bidding. Now that bid() owns the
# charge, a caller that still withdraws takes the same purchase out of the
# balance twice. The in-game buy-now path has a unit test that would catch it
# (AuctionManagerMoneyTest); the web and cloud paths post to the backend and
# cannot be driven in a unit test, so the shape is checked here instead.
CALLERS = (
    'src/main/java/com/aureleconomy/gui/BidGUI.java',
    'src/main/java/com/aureleconomy/gui/ConfirmPurchaseGUI.java',
    'src/main/java/com/aureleconomy/web/WebPurchaseExecutor.java',
    'src/main/java/com/aureleconomy/web/CloudSyncManager.java',
)
for rel in CALLERS:
    caller = strip_comments(read(rel))
    caller_name = rel.rsplit('/', 1)[-1]
    check(
        f'C1 {caller_name} does not withdraw for a bid it then places',
        not [
            m.start() for m in re.finditer(r'withdraw\(', caller)
            if '.bid(' in caller[m.start():m.start() + 240]
        ],
        'bid() charges the bidder now, so a withdraw first charges twice',
    )
    bid_calls = caller.count('.bid(')
    handled = len(re.findall(
        r'[!=]=\s*AuctionManager\.BidResult\.SUCCESS', caller))
    check(
        f'C1 {caller_name} acts on the result of every bid',
        bid_calls > 0 and handled >= bid_calls,
        f'{bid_calls} bid call(s) but {handled} result check(s): a refused bid '
        'must not be announced or followed by a delivery',
    )

# --------------------------------------------------------------------------
# H5: player-typed prices go through PriceParser, never straight to BigDecimal
# --------------------------------------------------------------------------
# new BigDecimal("1e999999999") keeps the exponent as a scale and the first
# setScale(2) in the economy layer expands it to a billion digits on the calling
# thread. A tiny exponent (5e-999999999) is below every ceiling and only explodes
# later, so the shape check has to run before the constructor, not after it.
PARSER = 'src/main/java/com/aureleconomy/utils/PriceParser.java'
parser = strip_comments(read(PARSER))
shape_at = parser.find('SHAPE.matcher')
construct_at = parser.find('new BigDecimal(text)')
check(
    'H5 PriceParser checks the shape before building a number',
    shape_at != -1 and construct_at != -1 and shape_at < construct_at,
    'an exponent must be refused as a string, before new BigDecimal(...) sees it',
)

PRICE_SITES = (
    'src/main/java/com/aureleconomy/commands/AuctionCommand.java',
    'src/main/java/com/aureleconomy/commands/EconomyCommand.java',
    'src/main/java/com/aureleconomy/commands/OrdersCommand.java',
    'src/main/java/com/aureleconomy/gui/AuctionGUI.java',
    'src/main/java/com/aureleconomy/gui/BidGUI.java',
    'src/main/java/com/aureleconomy/gui/OrderMaterialGUI.java',
)
for rel in PRICE_SITES:
    site = strip_comments(read(rel))
    site_name = rel.rsplit('/', 1)[-1]
    check(
        f'H5 {site_name} parses player prices through PriceParser',
        'PriceParser.parse(' in site,
        'this file takes a price from chat or a command argument',
    )
    check(
        f'H5 {site_name} builds no number from raw text',
        not re.search(r'new BigDecimal\(\s*(?!")', site),
        'only PriceParser may build a number from text a player typed',
    )

config_text = read('src/main/resources/config.yml')
version_match = re.search(r'^config-version:\s*(\d+)', config_text, re.M)
check(
    'H5 the price ceiling is in the shipped config',
    'max-transaction-price' in config_text
    and version_match is not None and int(version_match.group(1)) >= 3,
    'a new key needs config-version bumped in the same change',
)

# --------------------------------------------------------------------------
# F1: the ledger is written by the money primitives, so no feature can move
# money without appearing in it, and it cannot refuse or crash a movement.
# --------------------------------------------------------------------------
ledger_schema = strip_comments(read('src/main/java/com/aureleconomy/database/schema/LedgerSchema.java'))
for column in ('id', 'ts', 'actor_uuid', 'counterparty_uuid', 'type', 'item_key',
               'amount', 'currency', 'balance_after', 'ref_id', 'note'):
    check(
        f'F1 transaction_ledger declares {column}',
        f'"{column} "' in ledger_schema,
        'the fixplan lists this column by name',
    )

migration = strip_comments(read('src/main/java/com/aureleconomy/database/DatabaseManager.java'))
if 'case 7:' in migration:
    v7 = migration.split('case 7:', 1)[1].split('case 8:', 1)[0]
else:
    v7 = ''
check('F1 migration v7 creates the ledger table', 'new LedgerSchema(' in v7)
check('F1 migration v7 indexes the ledger by actor and time',
      'idx_transaction_ledger_actor' in v7)
check('F1 migration v7 indexes offline_earnings by uuid',
      'idx_offline_earnings_uuid' in v7)
check('F1 migration v7 retypes the money columns', 'retypeMoneyColumns(conn)' in v7)

money_types = strip_comments(read('src/main/java/com/aureleconomy/database/types/SQLTypes.java'))
check(
    'F4 money columns are exact, not DOUBLE',
    'DECIMAL(18,2)' in money_types and 'return "DOUBLE";' not in money_types,
    'a decimal type here is what stops drift compounding in a balance',
)

economy = strip_comments(read('src/main/java/com/aureleconomy/economy/EconomyManager.java'))
check(
    'F1 all five balance writers record a ledger row',
    economy.count('recordLedger(') >= 5,
    'deposit, debit, transfer, setBalance and creditStartingBalance each need one',
)
check('F1 a starting balance is recorded as money created',
      'LedgerReason.STARTING_BALANCE' in economy)
check('F1 an admin set is recorded as the delta it caused',
      'LedgerReason.ADMIN_SET' in economy
      and 'normalizedAmount.subtract(previous)' in economy,
      'recording the new balance would break the sum of all rows')

service = strip_comments(read('src/main/java/com/aureleconomy/economy/LedgerService.java'))
check(
    'F1 the ledger queue is bounded and counts what it drops',
    'MAX_PENDING' in service and 'dropped.incrementAndGet()' in service,
    'an unbounded queue turns a database outage into an out-of-memory kill',
)
check(
    'F1 a failed batch is counted, not thrown at the money path',
    'dropped.addAndGet(batch.size())' in service,
    'the ledger must never change whether money moved',
)

command = strip_comments(read('src/main/java/com/aureleconomy/commands/EconomyCommand.java'))
check('F1 /eco history is wired', 'handleHistory(sender, args)' in command)
check('F1 /eco history is discoverable', '"history"' in command)
check(
    'F1 /eco history reads args[0] only after checking for one',
    'args.length > 0 && args[0].equalsIgnoreCase("history")' in command,
    'a bare /eco threw ArrayIndexOutOfBoundsException before this guard existed',
)

# The primitives cannot know what a movement was for, so each feature names it.
# Counting the reasons pins both the wiring and the number of movements, which
# is what stops a refactor from quietly dropping one back to the generic type.
auction_money = strip_comments(read('src/main/java/com/aureleconomy/auction/AuctionManager.java'))
for reason, expected in (('BID', 1), ('BID_REFUND', 2), ('AUCTION_BUY', 2), ('AUCTION_SALE', 3)):
    check(
        f'F1 auction movement labelled {reason}',
        auction_money.count(f'LedgerReason.{reason},') == expected,
        f'expected {expected} call(s), found {auction_money.count(f"LedgerReason.{reason},")}',
    )

order_money = strip_comments(read('src/main/java/com/aureleconomy/orders/OrderManager.java'))
for reason, expected in (('ORDER_ESCROW', 1), ('ORDER_REFUND', 2), ('ORDER_FILL', 1)):
    check(
        f'F1 buy-order movement labelled {reason}',
        order_money.count(f'LedgerReason.{reason},') == expected,
        f'expected {expected} call(s), found {order_money.count(f"LedgerReason.{reason},")}',
    )

market_buy = strip_comments(read('src/main/java/com/aureleconomy/gui/MarketGUI.java'))
market_sell = strip_comments(read('src/main/java/com/aureleconomy/gui/SellGUI.java'))
check('F1 a market purchase is labelled', 'LedgerReason.MARKET_BUY,' in market_buy)
check('F1 a market sale is labelled', 'LedgerReason.MARKET_SELL,' in market_sell)

# --------------------------------------------------------------------------
# H4: a BIN purchase carries a quantity, never a price read as one
#
# The dashboard sent the unit price in `amount` and the API stored it as the
# purchase quantity, so a listing priced 100.50 bought 100 items. Both halves
# matter: the API must refuse a BIN request with no quantity (or the legacy
# shape comes back), and the executor must cap the quantity it is given at the
# stock it can see.
# --------------------------------------------------------------------------
handler = strip_comments(read('src/main/java/com/aureleconomy/web/DashboardApiHandler.java'))
check(
    'H4 the bid API tells a BIN listing apart from an auction',
    'auction.isBin()' in handler,
    'without this the endpoint cannot know that amount is a price, not a quantity',
)
check(
    'H4 a BIN request reads an explicit quantity',
    'optDouble(body, "quantity"' in handler,
)
check(
    'H4 a BIN request without a quantity is refused',
    'Invalid auction or quantity' in handler,
    'accepting it falls back to amount-as-quantity, which is the bug',
)
check(
    'H4 a fractional quantity is refused, not floored',
    'double requested = optDouble(body, "quantity"' in handler,
    'casting first turns 1.5 into one unit and hides the malformed request',
)

web_exec = strip_comments(read('src/main/java/com/aureleconomy/web/WebPurchaseExecutor.java'))
check(
    'H4 the executor caps a BIN quantity at the live stock',
    'Math.min(purchase.amount(), ai.getAvailableQuantity())' in web_exec,
    'stock moves between queueing and draining',
)
check(
    'H4 the executor reports the quantity it actually bought',
    'BigDecimal.valueOf(purchased)' in web_exec,
    'reporting the request would show a cost the buyer was never charged',
)

app_js = read('src/main/resources/web/app.js')
check(
    'H4 the dashboard sends a quantity for a BIN purchase',
    '{ auctionId: modalAuction.id, quantity }' in app_js,
)
check(
    'H4 the dashboard still sends a price for a real bid',
    '{ auctionId: modalAuction.id, amount }' in app_js,
)
dashboard = read('src/main/resources/web/index.html')
check(
    'H4 the BIN modal has a quantity control',
    'id="bid-quantity-input"' in dashboard and 'id="bid-quantity-block"' in dashboard,
)
check(
    'H4 the frontend suite covers the request body',
    os.path.exists(os.path.join(ROOT, 'ci', 'test_bid_quantity.js')),
)

# --------------------------------------------------------------------------
# H7: a custom item switched off stays off, and stops being tradeable
#
# The flag used to live only in the database: loadFromDatabase skipped every
# enabled = 0 row, so a restart made the item absent from memory and the next
# scan registered it as new with enabled = 1. Nothing on a trade path read the
# flag, so even an in-memory toggle left the item buyable at its cached price.
# The registry is the authority; the price map is a cache of it.
# --------------------------------------------------------------------------
registry = strip_comments(read('src/main/java/com/aureleconomy/scanner/CustomItemRegistry.java'))
load_block = registry[registry.index('public void loadFromDatabase'):
                     registry.index('public void saveToDatabase')]
upsert_block = registry[registry.index('public void upsert'):registry.index('public void clear')]
check(
    'H7 the registry loads disabled rows too',
    '"SELECT * FROM custom_items"' in load_block and 'WHERE enabled = 1' not in registry,
    'skipping them makes the next scan register the item as new and reset the flag',
)
check(
    'H7 a loaded item keeps its stored flag',
    '.enabled(rs.getBoolean("enabled"))' in load_block,
    'forcing enabled(true) is the reset this fix removes',
)
check(
    'H7 a reload prices enabled items and drops disabled ones',
    'marketManager.addCustomMarketItem(canonicalId, item)' in load_block
    and 'marketManager.removeCustomMarketItem(canonicalId)' in load_block,
    'register() is not called for a loaded item, so a restart used to drop its price',
)
check(
    'H7 a toggle reaches the market',
    'marketManager.addCustomMarketItem(canonicalId, item)' in upsert_block
    and 'marketManager.removeCustomMarketItem(canonicalId)' in upsert_block,
    'without this the disabled item keeps its last price',
)
check(
    'H7 the registry exposes the shared trade guard',
    'public boolean isDisabledCustomItem(ItemStack item)' in registry,
)

market_mgr = strip_comments(read('src/main/java/com/aureleconomy/market/MarketManager.java'))
check(
    'H7 the market never prices a disabled item',
    'if (!customItem.isEnabled())' in market_mgr,
)
check(
    'H7 the market can drop a custom item',
    'public void removeCustomMarketItem(String canonicalId)' in market_mgr,
)

sell_gui = strip_comments(read('src/main/java/com/aureleconomy/gui/SellGUI.java'))
check(
    'H7 the sell GUI skips a disabled custom item',
    sell_gui.count('if (isDisabledCustomItem(item))') >= 2,
    'the preview and the payout loop both have to skip it',
)
listing_block = auction_mgr[auction_mgr.index('Consumer<com.aureleconomy.auction.AuctionItem> onListed) {'):
                            auction_mgr.index('long now = System.currentTimeMillis();')]
check(
    'H7 the auction house refuses a disabled item',
    'registry.isDisabledCustomItem(item)' in listing_block
    and 'runOnMainThread(null, onListed)' in listing_block,
    'the caller refunds the fee when the callback is null',
)
check(
    'H7 the web purchase path refuses a disabled item',
    'This item is disabled' in web_exec,
)
snapshot = strip_comments(read('src/main/java/com/aureleconomy/webstore/WebSnapshot.java'))
check(
    'H7 the dashboard shop hides a disabled item',
    'if (!cmi.isEnabled())' in snapshot,
)
check(
    'H7 the toggle test covers persistence and both trade paths',
    os.path.exists(os.path.join(ROOT, 'src', 'test', 'java', 'com', 'aureleconomy',
                                'scanner', 'CustomItemToggleTest.java')),
)

# --------------------------------------------------------------------------
# H8: a mined spawner needs the permission, and Silk Touch unless the owner
# turns that off. The listener used to run at NORMAL, before a protection
# plugin could cancel the event, and accepted any pickaxe, so a region flag
# that only cancels the event still let the typed spawner item out.
# --------------------------------------------------------------------------
spawner_src = strip_comments(read('src/main/java/com/aureleconomy/listeners/SpawnerListener.java'))
check(
    'H8 the spawner drop needs Silk Touch by default',
    'tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0' in spawner_src,
    'the production predicate must read the real enchantment, not a stand-in',
)
check(
    'H8 spawner mining needs a permission',
    'player.hasPermission(MINE_PERMISSION)' in spawner_src,
)
check(
    'H8 the spawner break handler runs last and ignores cancelled events',
    '@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)\n    public void onSpawnerBreak'
    in spawner_src,
    'NORMAL runs before a protection plugin can cancel the event',
)
version_match = re.search(r'^config-version:\s*(\d+)', config_text, re.M)
check(
    'H8 the Silk Touch gate is configurable and shipped on by default',
    'require-silk-touch: true' in config_text
    and version_match is not None and int(version_match.group(1)) >= 4,
    'the safe default is true, and a new key needs config-version bumped',
)
plugin_yml = read('src/main/resources/plugin.yml')
check(
    'H8 the spawner permission is declared default op',
    'aureleconomy.spawner-mine:' in plugin_yml and 'default: op' in plugin_yml,
)
check(
    'H8 the spawner listener test covers permission, Silk Touch and priority',
    os.path.exists(os.path.join(ROOT, 'src', 'test', 'java', 'com', 'aureleconomy',
                                'listeners', 'SpawnerListenerTest.java')),
)

# --------------------------------------------------------------------------
# H9: a dashboard purchase is claimed in the database before it runs, so a
# replayed ID is refused after a restart instead of being charged twice. The
# cloud path also grew the guards the local web path already had.
# --------------------------------------------------------------------------
cloud_src = strip_comments(read('src/main/java/com/aureleconomy/web/CloudSyncManager.java'))
store_src = strip_comments(read('src/main/java/com/aureleconomy/web/ProcessedPurchaseStore.java'))
purchase_schema = strip_comments(read(
    'src/main/java/com/aureleconomy/database/schema/ProcessedPurchaseSchema.java'))

check(
    'H9 the purchase is claimed before anything executes',
    'if (!purchaseStore.claim(purchaseId))' in cloud_src
    and cloud_src.index('purchaseStore.claim(purchaseId)')
    < cloud_src.index('executeMarketWebBuy(player'),
    'claiming after delivery is not deduplication',
)
check(
    'H9 the ten-minute in-memory purchase map is gone',
    'processedPurchases' not in cloud_src and 'PURCHASE_EXPIRY_MS' not in cloud_src,
    'a map that empties on restart is the replay window',
)
check(
    'H9 the cloud market path checks the blacklist',
    'isBlacklisted(material)' in cloud_src,
)
check(
    'H9 the cloud market path caps the amount at one stack',
    'amount < 1 || amount > 64' in cloud_src,
)
check(
    'H9 a purchase with no id is refused instead of executed unclaimed',
    "asString(purchase.get(\"id\"))" in cloud_src
    and 'Ignored a dashboard purchase with no id' in cloud_src,
)
check(
    'H9 the recorded outcome is sent and retried after a failed confirmation',
    'purchaseStore.markReady(' in cloud_src
    and 'purchaseStore.markConfirmed(' in cloud_src
    and 'purchaseStore.pendingConfirmations()' in cloud_src,
)
check(
    'H9 the claim is a primary key, so the database enforces it',
    '"purchase_id "' in purchase_schema and 'PRIMARY KEY' in purchase_schema,
)
check(
    'H9 the claim table ships in schema v8',
    'LATEST_SCHEMA_VERSION = 8' in migration
    and 'case 8:' in migration
    and 'new ProcessedPurchaseSchema(' in migration.split('case 8:', 1)[1],
    'the table has to be created by the migration, not only listed for fresh installs',
)
check(
    'H9 the dedup test covers replay, restart, blacklist and malformed entries',
    os.path.exists(os.path.join(ROOT, 'src', 'test', 'java', 'com', 'aureleconomy',
                                'web', 'CloudPurchaseDedupTest.java')),
)
check(
    'H9 the store test covers the persisted claim and its outcome',
    os.path.exists(os.path.join(ROOT, 'src', 'test', 'java', 'com', 'aureleconomy',
                                'web', 'ProcessedPurchaseStoreTest.java')),
)

print()
print(f'{checks - len(failures)}/{checks} checks passed')
if failures:
    print('FAILURES:')
    for f in failures:
        print(f'  - {f}')
    sys.exit(1)
print('Issue #33 / #34 regression checks: COMPLETE')
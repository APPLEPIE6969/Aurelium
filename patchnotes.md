# Aurelium - Patch Notes

## v1.5.4.1 - Dashboard Market Item Identity, and an honest money API

### Breaking Changes

- **Money operations are now synchronous on the calling thread (economy)** *(fixes C3-core)* - `withdraw`, `withdrawIfSufficient` and `deposit` used to update the balance cache immediately, queue the database write on an async task, and return. They now hold the write lock, write, and return the row count of the write itself. On a server whose database is remote this means the calling thread waits for the write, so **an admin will see main-thread latency on money operations that used to be instant**. This is not incidental: a method that returns before its write lands cannot report whether the write happened, and every money caller in the plugin was being told the money moved when it might not have. The read path is unchanged, `getBalance` already read through to the database on a cache miss.

- **Two web money paths now refuse rather than proceeding on a refused write (web)** *(fixes H3c)* - a web or cloud purchase whose charge the database refused now reports "Not enough funds" and delivers nothing, and a web sale whose payout failed now reports the failure and removes nothing. Neither path silently continued before.

### Security

- **Web purchases resolve the item from the entry, not the material (web)** *(fixes H3)* - `WebPurchaseExecutor.buy` and `CloudSyncManager.executeMarketWebBuy` both ended in `new ItemStack(material, amount)`. For a named entry that is the wrong item: the spawner's mob lives in its `BlockStateMeta` and the book's enchant lives in its `EnchantmentStorageMeta`, neither of which survives a plain `new ItemStack`. A player was charged the custom price and handed an empty spawner or an empty book. Both now call the factory.

- **Web sell may only take the item the entry names (web)** *(fixes H3b)* - `WebPurchaseExecutor.sell` resolved the price key to a `Material` and then counted and removed stacks with `getType()`. A key that failed to parse fell back to `Material.SPAWNER`. Every spawner is `Material.SPAWNER` and every enchanted book is `Material.ENCHANTED_BOOK`, so the material carries no identity at all for exactly the entries that need one. A player holding one bare spawner, or one pig spawner, could post a sale for `SPAWNER_IRON_GOLEM_SPAWNER` repeatedly: `countInInventory` counted it, `removeFromInventory` took it, and `deposit` paid the iron golem spawner price. Counting and removal now compare `isSimilar` against the stack the factory builds for the entry, and both use the *same* stack, so a stack can never be counted as one item and removed as another. `countSimilar` caps at the amount requested so a refusal cannot claim more than was asked for. `countInInventory` is kept for the fill-order path, which uses a material count only as an upper bound and delegates the real removal to `OrderManager.fillOrder`.

- **A refused charge no longer delivers an item, and a failed payout no longer eats items (web)** *(fixes H3c)* - `buy` called `withdraw` and ignored the answer, then handed the item over unconditionally. A web or cloud purchase that raced another one on the same account could be charged nothing and still receive the item. `sell` did the mirror image: it removed the seller's items first and then deposited, ignoring the deposit, so a failed payout destroyed the items for nothing. That is a real outcome rather than a theoretical one, since the scheduled trade volume query on the shared connection has been observed failing with "the database has been closed". Buy now charges before delivering and records no trade volume on a refusal; sell now pays before taking. An unknown price key is refused before any money moves in either direction.

- **The money API reports what the database actually did (economy)** *(fixes C3-core)* - `withdraw` and `withdrawIfSufficient` decided affordability from the balance cache, queued the guarded `UPDATE ... AND balance >= ?` asynchronously, and returned before it ran. When the cache and the database disagreed, which is exactly what happens when a web purchase and an in-game purchase touch one account at once, the UPDATE matched no rows, the old code reloaded the balance and gave up quietly, and the caller was still told the money had been taken. All three money methods now return the real row count of the write. The guarded UPDATE is the only authority on affordability, so the cache no longer over-refuses either.

  New `transfer(from, to, amount, currency)` runs both legs in one transaction on the shared connection under the write lock, so a failure on either leg rolls the other back. Transferring to yourself is a successful no-op.

  Scope lock for this release: the only call sites moved onto the honest API are the two web money legs above. The auction bid path and every other caller keep their current behaviour and are migrated in v1.5.5. This is deliberate. Two consumers is the smallest possible canary for a change to the money API, with the full suite watching it, and the rest of the plugin should not be the first thing to meet it.

- **`MarketItemFactory` (market, new file)** - extracted from the private `ShopGUI.buildTransactionItem`, which was already correct, which is why the in-game path was unaffected and the two web paths were not. `ShopGUI.buildTransactionItem` is now a one-line delegation, so all three call sites share one implementation. Exposes `build`, `build(plugin, entry, amount)`, `entryForKey` and `buildForKey`. An unresolvable key returns `null` and callers refuse on it rather than substitute a default.

  Key resolution goes through the entry cache only. The old code guessed a material from the key, first assuming `SPAWNER` and later falling back to `Material.matchMaterial`. Every market item, plain or named, is cached under the same key its price is stored under, so a key missing from the cache has no price and the caller refuses it on price regardless. The guess could therefore only ever reach a refusal, while still being able to construct an item that is not a market entry at all.

  Verified against all 80 spawner entries, all of which map to a real `EntityType`, and all 125 enchanted book entries.

### Tests

- **`EconomyMoneyApiTest` (economy, new file, 19 tests)** - over a real SQLite file, built from the production `Schema` classes rather than copied. Covers all six cases the fix plan requires. Two fail on the old verdict, reproduced inside the new signature by deciding from the cache and reporting success without reading the row count: a stale cache claiming affordability still produced a phantom withdrawal, and a stale cache in the other direction refused a withdrawal the database could fund.

- **`MoneyInvariantTest` (economy, new file)** - the ledger harness every later money test uses. It sums `player_balances`, `offline_earnings` and the unfilled remainder of every active buy order, which is where escrow lives. It deliberately asserts three things rather than one: `assertMoneyUnchanged` for value merely moving between accounts, `assertMoneyMintedBy`, and `assertMoneyBurnedBy`. A withdrawal out of thin air is a burn rather than a movement, because nothing in the ledger owes that player anything, and a test that withdraws and expects the sum unchanged is asserting that the money came from nowhere.

- **`WebSellIdentityTest` (web, new file, 13 tests)** - drives the private `sell` method reflectively, since `PurchaseQueue.Purchase` is package private in `com.aureleconomy.webstore`. Every held stack reports `SPAWNER` or `ENCHANTED_BOOK` from `getType()`, whichever identity it claims, which is what a bare spawner, a pig spawner and an iron golem spawner all really do, so only `isSimilar` can tell them apart. Fails 3 of 13 when the comparisons are reverted to `getType()`, including `a bare spawner was accepted as an iron golem spawner`. Fails `a failed payout leaves the seller's items alone` when the payout is moved back after the removal.

- **`WebBuyChargeTest` (web, new file, 4 tests)** - drives the private `buy` method the same way. `a refused charge delivers nothing at all` fails when the charge check is removed, and reports that the buyer received the item anyway. `a refused charge moves no trade volume` guards the other half, because recording volume for a sale that never happened walks the market price on phantom trades. `InventoryUtils.hasSpace` is stubbed, since it is a real static call that asks the material for its max stack size and reaches `org.bukkit.Registry`.

- **`MarketItemFactoryTest` (market, new file, 13 tests)** - covers the dispatch and the mob type, and walks every spawner and book entry in `MarketItems`. Fails 8 of 13 on the pre-fix behaviour.

- **`ci/test_currency.js` (ci, rewritten)** - was failing at HEAD and unrelated to this work. Commit `af05452` ("drop the currency badge from market cards") deleted `currencyBadge` from `app.js`, but the test still sliced a region out of `updateBalanceDisplay` and asserted the symbol was stripped from the balance string. The badge was removed deliberately, so asserting on it could never pass again. Rewritten against the current behaviour, the shared `money(amount, currency, symbol)` helper that replaced it. Decimal-separator assertions are locale-agnostic, because `toLocaleString` groups per the viewer's locale and the suite would otherwise fail on a comma-decimal machine.

### Notes

- Both web item-construction test classes and the factory test intercept `new ItemStack` with `Mockito.mockConstruction` rather than really constructing stacks. `new ItemStack` reaches `Material.asItemType` and then `org.bukkit.Registry`, which throws `IllegalStateException: No RegistryAccess implementation found` with no server running. Every existing test in this suite already mocks `ItemStack` for the same reason. A stub `RegistryAccess` was considered and rejected: it would have made the enchantment assertions pass against a fake, which is worse than not testing them.
- `MarketItems.createEnchantedBook` is pre-existing code reached through the factory and is not retested. Parsing it needs real `Enchantment` instances, and the fix is only that the factory reaches it at all with the entry's stored name, which is what the two web paths never did.
- `SellGUI` does match by `getType()` when it pulls items into the sell area, but `calculateAndPrompt` then prices every item from its own key, reading the spawner's block state, so a pig spawner pulled in by type is priced as a pig spawner. The in-game path is not exploitable, which is consistent with the fix plan rating it M2 and medium rather than critical. `CloudSyncManager` has no material-only matcher on a sell route; the one `getType()` comparison it has is the fill-order gate whose real removal is delegated.
- No config or schema change. `config-version` stays at 2 and `LATEST_SCHEMA_VERSION` stays at 6.
- Version bumped to `1.5.4.1` in `build.gradle.kts`, in both `plugin.yml` files, and in all three module build files, which each hardcode the release string three times (`version`, `archiveBaseName`, and the `Implementation-Version` manifest attribute).

## v1.5.4 - Version Checker & Cloud Dashboard Hardening

### Breaking Changes

- **`web.local.host` is now respected (web)** - the local dashboard's HTTP server was hardcoded to listen on every network interface, so the `host` setting in `config.yml` did nothing and `localhost` was never actually enforced. On a publicly reachable Minecraft host this exposed the dashboard, and the session link in its URL in cleartext, to anyone who found the port. The server now binds where your config says. **If you run `mode: local` and reach the dashboard from another machine, add `host: "0.0.0.0"` under `web.local`** - a wildcard bind is still supported and the plugin now warns you in the console when you use one.

### Security

- **Session endpoints now require the server API key (web)** - the endpoints that create and update player sessions, and the one that confirms a purchase, were not checking credentials. Anyone who knew or could guess a server ID could have created a session for it and read player balances, or confirmed purchases on its behalf. All three now require the server's API key and reject anything else. If you run a local dashboard, the dashboard's own session handling was unaffected, but please rotate your API key out of caution.
- **Local dashboard is protected against framing (web)** - any website could previously embed your dashboard in an iframe and clickjack the Buy and Sell buttons. `Content-Security-Policy`, `X-Frame-Options` and `X-Content-Type-Options` are now sent, framing is restricted to your own origin, and player data is marked `no-store` so it is not kept in browser caches. The content security policy needs no exceptions, so there is no list of hashes to maintain when the dashboard changes.
- **Sessions can be revoked on demand (web)** - a new `POST /logout` endpoint invalidates a session link immediately, rather than leaving it usable until an hour of inactivity passes. Sessions were already cancelled when a player disconnects.

### Plugin Changes

- **Updated to the current stable Paper builds** - the CI matrix previously compiled and tested against Paper 26.2 build 40 and 26.1.2 build 72, both of which have since shipped newer stable builds. The matrix now uses 26.2 build 132 and 26.1.2 build 74, and the release job downloads the matching server jars so the integration tests run against the same builds you download. 1.21.11 stays on build 132
- **Version Checker**: On server startup, Aurelium now asynchronously queries the Modrinth API to check if you are running the latest version for your server's Minecraft version. If you are behind, a console message is logged:
    - `[Aurelium] You are X versions behind based on your server's Minecraft version. Latest is X.Y.Z, you have A.B.C.`
    - `[Aurelium] Download the latest version at: https://modrinth.com/plugin/aurelium/versions`
    - The check is non-blocking and runs on an async thread, so it does not impact server startup time
    - All log messages are prefixed with `[Aurelium]` so they are clearly identifiable
- **Cloud Dashboard Key Rotation**: The plugin now sends the previous API key as `X-Current-Api-Key` header during registration, allowing legitimate server restarts with rotated keys to re-register without manual intervention. On successful key rotation, the plugin automatically saves the current key as `prev-api-key` in config for future restarts
- **REGISTRATION_SECRET Support**: Admin-only endpoints on the web dashboard now require a `REGISTRATION_SECRET` environment variable to be set - rejects with 403 if missing, preventing unauthenticated access when the secret is not configured
- **Cloud dashboard has moved** - the dashboard is now hosted at `https://aurelium.alwaysdata.net`, replacing the previous address. Configs pointing at the old address, or at any other dashboard you had configured, are rewritten automatically on startup and the change is logged, so there is nothing to change by hand
- **RCON reconnection (web)** - the dashboard recovers on its own if the connection to the server drops, instead of requiring a restart
- **Reloads no longer stall on shutdown (web)** - the dashboard's request threads are now shut down cleanly when the plugin disables, so a `/reload` no longer leaves threads running or holds the port
- **Spawners are buyable again (market)** - every spawner read back as 0. All 52 spawners are `Material.SPAWNER`, so the material alone cannot tell them apart and each one is stored under a composite key made from the material and the display name, for example `SPAWNER_IRON_GOLEM_SPAWNER`. The fourteen read sites passed the display name on its own, `Iron Golem Spawner`, which is never a key that exists, so `getBuyPrice` returned its zero default. Spawners were unbuyable, valued at nothing on the dashboard, and wrote a flat 0 into price history. `MarketManager.entryKey()` now builds the key for both sides. Two sites that branched on whether an entry was a spawner were simplified rather than patched, since `entryKey` already falls back to the material key when there is no custom name, which removes the shape of the bug instead of leaving two branches to keep in step. No config change is needed
- **The plugin no longer overwrites dashboard data (cloud)** - the pending-purchase check was posting a 23-byte empty snapshot to `/api/sync`, which is the snapshot-publish route, every 30 seconds. Each poll replaced the server's real data with nothing. It now reads from a new read-only `GET /api/:server_id/pending-purchases`. A second fault was on the receiving end: axum's default body limit is 2 MB and a real snapshot is about 3.3 MB, so even a correct upload was rejected. The limit is now 16 MB, which leaves headroom on a small host without leaving the door open
- **Player session links are far smaller (cloud)** - the `/player` endpoint returned the entire item snapshot, about 3.3 MB, on every poll, and nothing read it. The snapshot has been removed, taking that response from 3339 KB to 0.3 KB
- **Purchases confirm without a timeout (cloud)** - the dashboard polls `GET /api/:server_id/purchase-status` to learn whether a purchase landed. That endpoint had never been implemented, so every buy waited the full 20 seconds and then reported a timeout regardless of outcome. It now returns the real state

### Dashboard Frontend

- **Buttons work under a strict content security policy** - the dashboard used inline click handlers, which a strict policy blocks outright. They are now bound from the page's own script, so the dashboard can be served with `script-src 'self'` and no exceptions. This is also why the security header added earlier in this release no longer breaks the Buy, Sell, Bid and confirm buttons
- **Google Analytics removed** - it required third-party script on a page showing player balances, and its inline bootstrap would have needed a policy exception. It was only ever configured with a placeholder ID, so no analytics data was being collected.
- **The price history chart can be opened again (web)** - the trend sparkline next to each item never opened anything, so the chart could not be reached from the dashboard at all, even though the code to draw it was already there. The sparkline is now a button that opens the price history for that item, and it works with the keyboard as well as the mouse. Items without enough history still show `no data` inline instead of opening a modal that has nothing to show
- **The price history chart is readable now (web)** - the canvas was drawn at half the size it was displayed at, so the axis labels came out about 5 pixels tall and the dates overlapped into an unreadable pile. The chart now draws at its real size, sharp on any display. The first and last dates are labelled, and the lowest price is drawn inside the plot so it no longer collides with them
- **Missing item icons no longer spam the console (web)** - the fallback that walks a chain of icon URLs when a texture fails to load was being blocked by the content security policy. It now works, and stops after the final attempt instead of retrying
- Apart from the sparkline now showing a hover and keyboard-focus outline, the security work in this release left the dashboard rendering identically before and after
- **Purchases no longer report a false timeout (web)** - see the purchase-status note above. The dashboard waits for the server to confirm, and the server had nothing to answer with, so a completed purchase still looked like a failure
- **The chart shows values on hover (web)** - a crosshair follows the pointer with a dot on the line and a tooltip giving the price and time. Price history for a page is fetched once and cached for the rest of the visit, instead of refetching all of it every time a chart opens
- **The chart window fits the chart (web)** - widened from 420px to 1100px, with the canvas at 1050 by 520
- **The currency badge is gone from market cards (web)** - it duplicated what was already on the card and pushed the prices out of alignment

### CI Changes

- **Cloud Registration CI Test**: New `cloud-registration-test` CI job that starts a fresh Paper server with a unique server-id/api-key, warms up the cloud dashboard, and verifies successful registration by grepping server logs for "Cloud dashboard registered" - catches registration regressions before release
- **VersionChecker CI Test**: Non-blocking test that verifies the Modrinth version check runs without errors (network failures are non-fatal)
- **MySQL verified on every run** - the checks that verify a MySQL-backed server writes correctly were not actually reading the values back, so they passed or failed for the wrong reason. They now check what they claim to, and the MySQL job is green for the first time
- **New guards against the regressions fixed in this release**: one checks the dashboard's parameter binding so a broken query cannot be mistaken for a database that is not writing, and another checks the local server's bind address, response headers and shutdown so the issues above cannot silently return
- Explicit timeouts on the long-running jobs, so a stuck server fails the run instead of holding it open
- **Test servers deregister themselves (ci)** - each CI job that registers a server with the production dashboard now deletes its registration afterwards, run with `if: always()` so it happens even when the job fails. New `DELETE /api/:server_id`, authenticated with the server's own key, plus `ci/cleanup-registration.sh`. It treats 401 and 403 as success, because the API cannot tell a wrong key from an absent server without leaking whether that server exists, and it always exits 0 so cleanup never masks a real failure. A backlog of 295 dead test servers was purged from the dashboard, taking its database from 132 MB to 3.4 MB
- **The restart cycle test pins its server id (ci)** - it previously let the plugin generate a random one that nothing recorded, so there was no way to clean the registration up afterwards

---

## v1.5.3 - Paper 26.2 Support & Web Dashboard Overhaul

### Plugin Changes

- **Paper 26.2 (build 40) Support**: Full compatibility with Paper 26.2 alpha — CI matrix now builds and tests against 26.2, 26.1.2 (build 72), and 1.21.11 (build 132)
- **ViaVersion 5.10.1-SNAPSHOT** & **ViaBackwards 5.10.1-SNAPSHOT**: Updated protocol support for cross-version connectivity
- **Node.js 24** in CI: Updated from Node 22 (deprecated June 2026) for mineflayer test jobs
- **SQLite Race Condition Fix**: `DatabaseManager.getConnection()` and `close()` now `synchronized` to prevent concurrent access issues on async threads
- **Cloud Dashboard Registration**: Failure logs now at `ERROR` level (was `WARN`) — registration failures are actionable and should be visible
- **CI Heredoc Fixes**: Corrected bash heredoc escaping in workflow YAML (`<< \'PROPS\'` vs `<< \\\'PROPS\\\'`) preventing server.properties corruption
- **MySQL 8.0 Compatibility**: Docker service configured with `mysql_native_password` auth plugin; JDBC URL includes `allowPublicKeyRetrieval=true`
- **54 In-Game CI Tests**: Full RCON-based test suite covering economy commands, auctions, orders, and web dashboard integration across all 3 Paper versions

### Website Changes ([WebMarketMC](https://github.com/APPLEPIE6969/WebMarketMC))

- **Astra DB Persistence**: Full rewrite of `server.js` with write-through cache, startup cache load from all pages (paginated), and automatic fallback to in-memory mode when no `ASTRA_TOKEN` is set
- **AES-256-GCM Field-Level Encryption**: All sensitive data stored in Astra DB is encrypted at rest — `api_key`, `session_token`, `player_uuid`, `balances_json`, and `result_json`. Uses deterministic HMAC-SHA256 for session primary keys (stable across restarts) and random-IV AES-256-GCM for everything else. Backward compatible: old plaintext data is read as-is. Server refuses to start with `ASTRA_TOKEN` but no `ENCRYPTION_KEY` (fail-closed)
- **Write-Through Integrity**: Failed Astra DB writes now return 503 to the caller instead of silently succeeding with cache-only data — no more data loss on restart after a failed write
- **Transport Error Handling**: `astraFetch()` and `astraQuery()` catch timeout/network errors and return structured `{ ok: false }` instead of throwing into route handlers
- **Session Cache Consistency**: `sessionCache` keyed by `tokenHash(token)` everywhere — startup load, session creation, lookup, and cleanup all use the same HMAC-based key, so persisted sessions survive restarts
- **Input Validation Hardening**: Buy amounts validated with `Number()` + `Number.isInteger()` (no more NaN bypass); auction/order IDs validated with `Number()` + `Number.isFinite()` + `Number.isInteger()` (no more partial parses like `parseInt('12abc')`)
- **Auction Modal Quantity Selector**: BIN auctions with stacked items now show +/- quantity buttons (1 to remaining), per-unit price, and live total cost calculation
- **Currency Display**: Uses each item's assigned currency instead of hardcoded dollars
- **Auction Quantity Display**: Listings show available quantity, remaining count, and per-item price
- **Auction Category Sidebar**: Auction page now has the same sidebar layout as the market page, with filters for All Listings, BIN Listings, and BID Listings
- **Empty State Messages**: Category filters with no results show "No auctions in this category" with smooth animation instead of falling back to showing all auctions
- **Modal Close Animations**: Confirm bid/purchase modals now animate closed smoothly instead of vanishing instantly
- **Quantity Button States**: Plus button grays out at listing limit, minus button grays out at quantity 1
- **BIN Modal Label**: Shows "Price (per item)" instead of "Your Bid (per item)" for buy-now listings
- **Minecraft Item Icon Fallback Chain**: 26.2 item -> 26.2 block -> 26.1 item -> 26.1 block -> 1.21.11 item -> 1.21.11 block -> box icon SVG
- **RAM-Based Registration Queue**: OOM protection with configurable limits (`MAX_RAM_MB=500`, `MAX_QUEUE_SIZE=50`) for Render free tier
- **CQL Injection Fix**: Parameterized queries in `astraQuery` function (was string interpolation)
- **Security Audit**: Verified no dupe/money bypass vulnerabilities in purchase flow; IDOR protection on purchase-status endpoint

---

## v1.5.2 - Custom Item Display Name Fix

### Fixes

- **Market Custom Item Display Names**: Discovered custom items now show their configured display name in the market (alerts, GUI, price lookups) instead of falling back to raw material name. Previously, `MarketManager.addCustomMarketItem()` created `MarketEntry` with only material and price, discarding the display name — auction messages already worked correctly, but market-side name resolution did not.

---

## v1.5.1 - Cloud Dashboard URL Fix

### Fixes

- **Cloud Dashboard Registration**: Fixed cloud dashboard sometimes not registering.

---

## v1.5.0 - Custom Item Scanner & Stability Improvements

**Aurelium now automatically discovers custom items from popular third-party plugins and integrates them seamlessly into your server market—no manual configuration required.**

### New - Auto Custom Item Detection (Scanner)

- **UnifiedItemScanner**: Automatically detects custom items from installed third-party plugins on server startup and via `/customitems scan`
  - Supported plugins (via reflection, zero hard dependencies): ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item
  - Each plugin API is accessed via reflection only—no compile-time dependencies required
- **Cross-Plugin Deduplication**: If the same item is registered by multiple plugins (e.g., ItemsAdder ruby_sword and MMOItems SWORD:RUBY_BLADE), it is deduplicated by canonical ID and stored as a single entry in `custom_items`
- **Config Override Sync**: Discovered items are written to `config.yml` under `discovered-items:` with source plugin, display name, material type, and default buy/sell prices
  - Server owners can edit prices/flags in config, and changes persist across restarts
- **Database schema v2**: Added `custom_items` table for persistent custom item tracking with automatic v1-to-v2 migration
- **Thread-Safe Scanning**: All scan operations run async with proper locking to avoid race conditions during startup
- Custom items appear in the market with proper display names and configurable pricing

### New - /customitems Command

- **Full management commands** for discovered custom items:
  - `/customitems scan` — Force rescan of all supported plugins
  - `/customitems list` — View all discovered custom items
  - `/customitems info <id>` — Show details for a specific item
  - `/customitems reload` — Reload config overrides from disk
  - `/customitems toggle <id>` — Enable or disable a discovered item in the market
  - `/customitems price <id> <buy> [sell]` — Set buy/sell prices for a discovered item

### Fixes

- **DatabaseManager DDL Propagation**: `createTables()` now re-throws `SQLException` if `custom_items` table creation fails, preventing schema version mismatch on fresh MySQL installs
- **Cloud Dashboard Retry Logic**: HTTP 4xx/5xx errors stop retrying immediately (permanent errors); only transient errors (network, DNS) retry with backoff
- **CustomItemRegistry Concurrency**: Fixed race conditions in `register()`, `upsert()`, and `clear()` — all write operations now use proper read-write locking
- **MarketItems Price Clamping**: Fixed inverted floor/ceiling clamping when `buyPrice` was unset (-1 sentinel), preventing price recovery drift toward -1
- **Auction Display Names**: Auction messages now show custom display names instead of raw material types (uses `PlainTextComponentSerializer` for safe Component handling)
- **MySQL 8.0.20+ Compatibility**: Replaced deprecated `VALUES(col)` syntax with modern `AS new` alias syntax in all upsert queries

### Testing

- Expanded MySQL CI test suite: scanner integration, dedup accuracy, schema migration, custom_items persistence
- Mineflayer in-game tests: 96/96 pass covering economy commands, auction lifecycle, and customitems subcommands
- All CI jobs pass: build, smoke-test, ingame-test, mysql-test, scanner-mysql, custom-item-detection, custom-items-persistence

### Platform

- Targets **Paper 26.1+** (Java 25, api-version: '26.1')
- Uses Paper's `RegistryAccess` / `RegistryKey` API for enchantment lookups
- CI tested against Paper 26.1.2

---

## v1.4.5 - MySQL & Auction House Fixes

**MySQL 8.0.20+ compatibility and auction display name improvements.**

### Fixes

- **MySQL 8.0.20+ Compatibility**: Replaced deprecated `VALUES(col)` syntax with modern `AS new` alias syntax in all upsert queries
- **Auction Custom Display Names**: Auction messages now show custom item display names instead of raw material types (uses `PlainTextComponentSerializer` for safe Component handling)
- **PreparedStatement Param Mismatch**: MySQL upserts now only set the parameters they actually use (4th param was SQLite-only)

### Testing

- Added expanded MySQL CI test suite (10 tests) running against MySQL 8.0 service container
- All 4 CI jobs pass: build, smoke-test, ingame-test, mysql-test

### Platform

- Targets **Paper 26.1+** (Java 25, api-version: '26.1')
- Uses Paper's `RegistryAccess` / `RegistryKey` API for enchantment lookups
- CI tested against Paper 26.1.2

---

## v1.4.3 - CI & Testing Infrastructure

**Automated in-game testing ensures every command works correctly on Paper 26.1.2.**

### Testing

- Added RCON-based in-game command testing to GitHub Actions CI (25 tests covering all commands)
- `/bal` variants, `/eco` admin commands, player-only command rejection
- All tests pass on every push

---

## v1.4.2 - Security & Performance Hardening

**This update is mandatory for all servers using the web dashboard.**

### Security

- Session tokens now use cryptographically secure `SecureRandom` (256-bit) instead of `UUID.randomUUID()`

### Performance

- Cloud sync retries no longer block ForkJoinPool threads (uses Bukkit scheduler)
- Offline earnings cleanup uses bulk DELETE instead of N+1 queries

### Fixes

- Fixed BungeeCord/Velocity balance sync — player data now refreshes from MySQL on server switch
- Added proper exception logging to previously empty catch blocks

---

## v1.4.1 - Web Stability Hotfix

### Fixes

- Cloud sync automatically reconnects if render server restarts (Fixes 403 Invalid server ID)

---

## v1.4.0 - Security, Enchantments & Cleanup

### New

- Enchantment books have individual prices based on rarity and level
- Added 1.21.11 enchantments: Breach, Density, Wind Burst
- Web dashboard balance updates instantly after in-game transactions

### Fixes

- `/bal` permission check fixed
- SellGUI total display fixed
- Market GUI prices update after transaction
- AH cancellation flicker fixed
- Collection bin item drop fix
- "All Items" button filter fix
- Cloud sync HTML spam fix

### Security

- Economy/market/auction transactions are atomic (no duping)
- Auction bids use price-checked SQL
- Web purchases deduplicated
- All GUIs lock shift-click and drag
- SellGUI locks price at review time
- CORS is configurable whitelist

### Internal

- All money math uses BigDecimal
- Market item prices moved to `market-items` in config

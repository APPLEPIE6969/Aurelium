# Aurelium - Patch Notes

## v1.5.4 - Version Checker & Cloud Dashboard Hardening

### Breaking Changes

- **`web.local.host` is now honoured (web)** - `WebServer.start()` hardcoded `new InetSocketAddress("0.0.0.0", port)` and never read the config key. `config.yml` shipped `host: "localhost"` and only `WebCommand` read it, to build the URL shown to the player. Anyone running `mode: local` on a publicly reachable host was serving the dashboard, and the session token in its URL in cleartext, on every interface. The socket now uses the configured value, so **local-mode operators who relied on remote access must set `web.local.host: "0.0.0.0"` explicitly.** A wildcard bind is still supported and now logs a warning naming the cleartext-token risk. The startup log reports the real bind host instead of always claiming `localhost`.

### Plugin Changes

- **Version Checker**: On server startup, Aurelium now asynchronously queries the Modrinth API to check if you are running the latest version for your server's Minecraft version. If you are behind, a console message is logged:
    - `[Aurelium] You are X versions behind based on your server's Minecraft version. Latest is X.Y.Z, you have A.B.C.`
    - `[Aurelium] Download the latest version at: https://modrinth.com/plugin/aurelium/versions`
    - The check is non-blocking and runs on an async thread, so it does not impact server startup time
    - All log messages are prefixed with `[Aurelium]` so they are clearly identifiable
- **Cloud Dashboard Key Rotation**: The plugin now sends the previous API key as `X-Current-Api-Key` header during registration, allowing legitimate server restarts with rotated keys to re-register without manual intervention. On successful key rotation, the plugin automatically saves the current key as `prev-api-key` in config for future restarts
- **REGISTRATION_SECRET Support**: Admin-only endpoints on the web dashboard now require a `REGISTRATION_SECRET` environment variable to be set - rejects with 403 if missing, preventing unauthenticated access when the secret is not configured
- **Dashboard moved to AlwaysData (web)** - the cloud URL now points at `https://aurelium.alwaysdata.net`, a Rust + SQLite service on the free `plus-free` plan's `user_program` site type, replacing the retired Render deployment. `CloudSyncManager.resolveDashboardUrl()` rewrites the retired host (and any custom one) on startup, persists it, and logs the change; old-config fixtures intentionally still contain the retired URL so the migration stays tested.
- **Session-minting endpoints now require the server API key (web)** - `POST /api/session`, `POST /api/session-update` and `POST /api/confirm-purchase` accepted requests with no credential check, so a caller who knew or guessed a `serverId` could mint a player session for it and read balances, or confirm a purchase. All three validate `X-Api-Key` against the registered server before touching the store and return 401 otherwise. Verified live: forged sessions with no key and with a wrong key both 401.
- **`POST /logout` revokes the presented token (web)** - added `WebSessionManager.invalidateToken(String)`, which removes the row and removes the player's index entry only when it still points at that token (`playerTokens.remove(uuid, token)`), so a stale link cannot kill a session created by a later `/web`. Previously a session could only be dropped by idling out or disconnecting.
- **Local dashboard response hardening (web)** - added a `Content-Security-Policy` matching the cloud backend's, plus `X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN` and `Referrer-Policy: no-referrer`. `frame-ancestors 'self'` is the part that matters here: without it any site could iframe the dashboard and clickjack the Buy/Sell buttons. HSTS is deliberately omitted because the server speaks plain HTTP. `applySecurityHeaders()` is applied to all three content-bearing responses including both hand-rolled 404s, and `DashboardApiHandler` sets `Cache-Control: no-store` because those responses are per-player balances.
- **Request pool leak on disable (web)** - `Executors.newFixedThreadPool(4)` was passed to `setExecutor` but never shut down. Its threads are non-daemon, so they outlived plugin disable and could stall a reload. The pool is now held in a field and `shutdownNow()` in `stop()`.
- **Icon fallback no longer depends on an inline handler (web)** - `itemIconTag()` built each market row icon with a literal `onerror="iconFallback(this, '...')"`. That is an inline event handler, so the strict CSP blocked it and every missing texture logged a violation. Now tagged `data-icon-fallback` and handled by one capture-phase listener on the document (`error` does not bubble). Added `data-icon-done` so a failing fallback icon cannot loop; verified termination at 6 error events over a 5-candidate chain.

### Dashboard Frontend (`src/main/resources/web`, mirrored to `aurelium-cloud/web`)

- **Handlers moved to data attributes** - 17 inline `on*` attributes became `data-close-modal` / `data-action` / `data-search`, bound in a new `bindDeclarativeHandlers()` called from `bindEvents()`. Binding skips any attribute that does not name a function on `window`, so the pre-existing delegated `[data-action][data-obj]` row handler is untouched and market rows are not double-handled.
- **The one inline style moved to CSS** - the header logo's `width/height/color` became `.logo-icon-svg`.
- **Google Analytics removed** - both the loader and its inline bootstrap were deleted. The ID was the placeholder `G-XXXXXXXXXX`, so no data was being collected, and its inline bootstrap would have required a `script-src` hash on a page displaying player balances.
- This is what makes the strict CSP possible without `unsafe-inline` or a hash list to maintain: `script-src 'self'` and `style-src 'self' https://fonts.googleapis.com`.
- Verified pixel-identical before and after against the live site: 0 of 1,400,000 pixels differ.

### Cloud Backend (`aurelium-cloud`, Rust)

- **Strict CSP, no hashes** - `script-src 'self'` with no `unsafe-inline`, no `unsafe-hashes` and no per-handler hashes, made possible by the frontend work above. `https://www.googletagmanager.com` was removed from `script-src`; it was still trusted after GA was deleted, which was pure attack surface.
- **Sessions no longer grow without bound** - the plugin mints a fresh 32-byte token per shop open and `sessions` is keyed on it, so every visit inserted a row and nothing removed one. Added `Store::prune_sessions(max_age_seconds)`, called at startup with a 96h default and overridable via `AURELIUM_SESSION_TTL_HOURS`. AlwaysData idle-stops the app after `max_idle_time`, so this runs at least every 30 minutes. A prune failure logs a warning and continues rather than blocking startup. Verified on production: a 200-day-old row was deleted and a fresh one kept. Four new store tests.
- **Deployment** - static musl binary built on CI, uploaded and swapped atomically (`.new` + `mv`) because AlwaysData keeps the old process running and Linux refuses to truncate a running executable (`ETXTBSY`). This cost an hour once, so `DEPLOY.md` documents verifying the remote checksum. The app runs in a namespace invisible over SSH, so a restart goes through the site API.
- **CI apt hardening** - the runner image lists `azure.archive.ubuntu.com` first, and when it is unreachable a bare `apt-get update` retries for ten-plus minutes before falling back, which parked a build. Each attempt now has a 15s timeout and one retry, three attempts are made with partial lists cleared between, and three failures exit loudly rather than hanging.
- Test suite is now 14 store + 23 integration, with clippy `-D warnings` and `cargo fmt --check` clean.
- Production cleanup: the six manual test servers and all seven sessions, including two attacker-chosen tokens minted during the security probe, were deleted from the live database. Backed up to `/tmp/aurelium-cloud.db.bak` first. Verified the old forged tokens now return 401.

### CI Changes

- **Cloud Registration CI Test**: New `cloud-registration-test` CI job that starts a fresh Paper server with a unique server-id/api-key, warms up the cloud dashboard (`https://aurelium.alwaysdata.net/health`), and verifies successful registration by grepping server logs for "Cloud dashboard registered" - catches registration regressions before release
- **VersionChecker CI Test**: Non-blocking test that verifies the Modrinth version check runs without errors (network failures are non-fatal)
- **`ci/test_mysql_binding.py` (new)** - the suite verifies persisted state by querying the plugin's database, and under MySQL that goes through the `mysql` client via `--execute`, so it binds placeholders itself. 18 checks covering substitution, escaping and placeholder counts. Pinned directly against the original defect by inspecting `Database.q`'s source, so a dropped argument cannot pass unnoticed again.
- **`ci/check_local_web_security.py` (new)** - 23 checks over `WebServer.java`, `DashboardApiHandler.java`, `WebSessionManager.java` and `config.yml`. Verifies each 200/404 response individually rather than counting helper calls, since a count can be satisfied while one path is left bare. Also asserts every `getComponentLogger()` call names a method that actually exists, which is how a `warning`/`warn` compile error was caught before it reached a build.
- **`ci/test_db_dialect.py` updated** - the `_mysql_query` test doubles used a one-argument lambda and broke when the method gained an optional `args` parameter.
- MySQL harness fixes: `Database.q()` forwarded SQL but **dropped `args`**, so every parameterised query reached the server with a literal `?`, errored, and was turned into an empty result by a non-zero exit. That is indistinguishable from "the plugin never wrote", and it is why all 22 economy, concurrency and restart assertions reported `persisted None` while the plugin was writing correctly. Substitution is now quote-aware so a `?` inside a string literal is left alone, escaped the way `mysql_real_escape_string` does rather than interpolated raw, raises on an arity mismatch, and records failures in `last_error`. Two SQLite-only assertions no longer run under MySQL: `PRAGMA journal_mode`, and `create_sql()`, which selected `create_statement` from `information_schema.tables` - a column that does not exist, so the query always errored and the empty result read as "no primary key".
- `mysql-test` is green for the first time: 0 failures out of the suite that previously reported 22.
- RCON reconnect/retry with dedicated tests, stale MySQL connection validation (`SELECT 1` on an interval plus JDBC options), explicit `timeout-minutes` on the long jobs, and a YAML indentation repair.

### Outstanding

- **The AlwaysData SSH password and API token were exposed in plaintext** during this work and have not been rotated. The API token has full account scope and should be treated as compromised.
- **CI registers against the production database**, leaving `ci-test-*` and Paper-registered rows in `servers` (130 at last check). Worth pointing at a separate database.
- `release.yml` still omits the `26.2` artifact, so no release has been cut since `v1.5.0`.
- A live trade loop from an actual Minecraft server through `confirm-purchase` is still unverified; `cloud-registration-test` covers registration only.

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

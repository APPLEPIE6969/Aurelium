# Aurelium - Patch Notes

## v1.5.3 - Paper 26.2 Support & Web Dashboard Overhaul

### Plugin Changes

- **Paper 26.2 (build 40) Support**: Full compatibility with Paper 26.2 alpha — CI matrix now builds and tests against 26.2, 26.1.2 (build 72), and 1.21.1111 (build 132)
- **ViaVersion 5.10.1-SNAPSHOT** & **ViaBackwards 5.10.1-SNAPSHOT**: Updated protocol support for cross-version connectivity
- **Node.js 24** in CI: Updated from Node 22 (deprecated June 2026) for mineflayer test jobs
- **SQLite Race Condition Fix**: `DatabaseManager.getConnection()` and `close()` now `synchronized` to prevent concurrent access issues on async threads
- **Cloud Dashboard Registration**: Failure logs now at `ERROR` level (was `WARN`) — registration failures are actionable and should be visible
- **CI Heredoc Fixes**: Corrected bash heredoc escaping in workflow YAML (`<< 'PROPS'` vs `<< \'PROPS\'`) preventing server.properties corruption
- **MySQL 8.0 Compatibility**: Docker service configured with `mysql_native_password` auth plugin; JDBC URL includes `allowPublicKeyRetrieval=true`

### Website Changes (WebMarketMC)

- **Astra DB Persistence**: Full rewrite of `server.js` with write-through cache, startup cache load, and fallback to in-memory mode
- **Field-Level Encryption**: AES-256-GCM encryption for sensitive fields (`api_key`, `session_token`, `player_uuid`, `balances_json`, `result_json`) — backward compatible with plaintext data
- **Auction Modal Quantity Selector**: BIN auctions with stacked items now show +/- quantity buttons (1 to remaining), per-unit price, and live total cost calculation
- **Currency Display**: Uses each item's assigned currency instead of hardcoded dollars
- **Auction Quantity Display**: Listings show available quantity, remaining count, and per-item price
- **RAM-Based Registration Queue**: OOM protection with configurable limits (`MAX_RAM_MB=500`, `MAX_QUEUE_SIZE=50`) for Render free tier
- **CQL Injection Fix**: Parameterized queries in `astraQuery` function (was string interpolation)
- **Security Audit**: Verified no dupe/money bypass vulnerabilities in purchase flow

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
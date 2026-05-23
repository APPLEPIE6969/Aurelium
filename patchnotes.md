# Aurelium - Patch Notes

## v1.4.5 - MySQL Compatibility, Auction Display Names & Custom Item Detection

**Critical MySQL fix, auction improvements, and automatic custom item scanning from third-party plugins.**

### New - Auto Custom Item Detection

- **CustomItemScanner**: Automatically detects custom items from installed third-party plugins at startup and on `/customitems scan`
  - Supported plugins (via reflection, zero hard dependencies): ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, SX-Item
  - Discovered items are saved to `config.yml` under `discovered-items:` with source plugin, display name, material type, and default buy/sell prices
  - Config overrides from `discovered-items:` are loaded at startup via `loadConfigOverrides()`, keeping config values in sync with discovered items
  - Scanner auto-runs on server start; rescan anytime with `/customitems scan`
- **`/customitems` command** for managing discovered custom items:
  - `/customitems scan` - Force rescan all supported plugins
  - `/customitems list` - List all discovered custom items
  - `/customitems info <id>` - Show details for a specific item
  - `/customitems reload` - Reload config overrides from disk
  - `/customitems toggle <id>` - Enable/disable a discovered item in the market
  - `/customitems price <id> <buy> [sell]` - Set buy/sell prices for a discovered item
- **Database schema v2**: Added `custom_items` table for persistent custom item tracking with automatic v1-to-v2 migration
- Custom items appear in the market with proper display names and configurable pricing

### Fixes

- **MySQL 8.0.20+ Compatibility**: Replaced deprecated `VALUES(col)` syntax with modern `AS new` alias syntax in all upsert queries. MySQL 8.0.20+ deprecates `VALUES(col)` and it will be removed in a future release -- this update ensures forward compatibility.
- **PreparedStatement Param Mismatch**: MySQL upserts now only set the parameters they actually use. Previously, the extra SQLite-only 4th parameter was set unconditionally, which was harmless but messy.
- **Auction Custom Display Names**: Auction messages (outbid, new offer, offer accepted, offline earning log) now show custom item display names instead of raw material types. A renamed Iron Helmet will now show its custom name, not "IRON_HELMET". Uses `PlainTextComponentSerializer` for safe Component handling -- no more `ClassCastException` risk from casting to `TextComponent`.

### Testing

- Added expanded MySQL CI test suite (10 tests) running against MySQL 8.0 service container
- All 4 upsert code paths exercised: `deposit()`, `setBalance()`, `loadBalance()`, `updatePlayerMetadata()`
- Zero `SQLSyntaxErrorException` confirmed on MySQL 8.0
- Added `isMySQL()` method to `DatabaseManager` for clean dialect detection
- All 4 CI jobs pass: build, smoke-test, ingame-test, mysql-test

### Platform

- Targets **Paper 26.1+** (Java 25, `api-version: '26.1'`)
- Uses Paper's `RegistryAccess` / `RegistryKey` API for enchantment lookups
- CI tested against Paper 26.1.2
- For Paper 1.21.x support, see the `feature/custom-item-scanner-1.21` branch

## v1.4.3 - CI & Testing Infrastructure

**Automated in-game testing ensures every command works correctly on Paper 26.1.2.**

### Testing

- Added RCON-based in-game command testing to GitHub Actions CI (25 tests covering all commands)
- `/bal` variants: self, other player, with currency -- all verified
- `/eco` admin commands: give, take, set, with currency, invalid inputs (negative, non-numeric, missing args, invalid currency, invalid action)
- Player-only commands reject console correctly: `/pay`, `/market`, `/web`, `/stocks`, `/ah` (4 subcommands), `/orders` (4 subcommands)
- Smoke test upgraded to Paper 26.1.2
- All tests pass on every push -- zero regressions guaranteed

### Internal

- Updated Paper CI server to latest build
- Bumped version to 1.4.3 across all build files and config

## v1.4.2 - Security & Performance Hardening

**This update is mandatory for all servers using the web dashboard.**

### Security

- Session tokens for the web dashboard now use cryptographically secure `SecureRandom` (256-bit entropy) instead of `UUID.randomUUID()` (122-bit), preventing potential token prediction attacks
- Added explanatory comments to `CloudSyncManager` for exceptions that are safely ignored

### Performance

- Cloud dashboard registration retries no longer block a `ForkJoinPool` thread for 15 seconds between attempts, replaced `Thread.sleep(15_000)` with Bukkit's non-blocking `runTaskLaterAsynchronously` scheduler
- Offline earnings cleanup now uses a single bulk `DELETE` SQL query instead of N+1 individual queries when a player joins, significantly reducing database load on servers with many offline earnings records

### Fixes

- Added proper exception logging to previously empty `catch` blocks in `ShopGUI` and `CloudSyncManager`, making debugging much easier
- Fixed a bug in `ShopGUI` where `target` was used instead of `clicker` for certain player interactions
- **BungeeCord/Velocity Sync**: Fixed a major bug where player balances could stay "stale" when switching servers due to permanent RAM caching. Player data is now refreshed from MySQL immediately upon joining a new server instance.

### Internal

- Bumped version in build config to 1.4.2
- Updated `README.md` to clarify that MySQL is mandatory for cross-server synchronization and that Market Prices remain per-server for regional economy support

## v1.4.1 - Web Stability Hotfix

### Fixes

- Cloud sync now automatically reconnects to the web dashboard if the render server restarts (Fixes 403 Invalid server ID error)

## v1.4.0 - Security, Enchantments & Cleanup

### New

- Enchantment books now have individual prices based on rarity and level (Mending = 35k, Sharpness V = 60k, etc.)
- Added 1.21.11 enchantments: Breach, Density, Wind Burst
- Web dashboard balance updates instantly after buying/selling in-game
- `/bal` now suggests currencies and online players in tab
- Auto database backups when the plugin updates
- Auto schema migrations on startup

### Fixes

- `/bal` was checking the wrong permission -- non-op players couldn't use it
- SellGUI sometimes showed a different total than what you actually got paid
- Market GUI prices now update right after a transaction instead of lagging behind
- AH cancellation visual flicker on shift-click
- Collection bin no longer drops items on the ground if your inventory is full
- "All Items" button was showing items outside the configured categories
- NullPointerException when browsing "All Items"
- Cloud sync no longer spams HTML in console when the server is waking up

### Security

- Economy, market, and auction transactions are now atomic (no more duping from race conditions)
- Auction bids use price-checked SQL to prevent out-of-order bid corruption
- Web purchases are deduplicated to prevent double-spending
- All GUIs lock down shift-click and drag to prevent inventory exploits
- SellGUI locks the price at review time so it can't change mid-transaction
- CORS is now a configurable whitelist instead of wildcard

### Internal

- All money math uses BigDecimal now (no more floating point drift)
- Market item prices stored under `market-items` in config (moved from `market.items`)
- Moved Beacon, Respawn Anchor, End Crystal to proper categories

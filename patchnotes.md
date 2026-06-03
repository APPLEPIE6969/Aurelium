# Aurelium - Patch Notes

## v1.5.0 - Custom Item Scanner & Stability Improvements

**Major feature release adding automatic custom item detection from third-party plugins, plus comprehensive stability and security fixes across economy, market, and web layers.**

### New
- **Custom Item Scanner**: Automatically detects custom items from ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, and Nexo plugins via reflection-based API integration
- **Cross-Plugin Deduplication**: Prevents duplicate custom item entries when multiple plugins define similar items — detected items are normalized by canonical ID before insertion
- **`/customitems` Command Suite**: New admin commands for managing discovered custom items: `toggle` (enable/disable in markets), `price` (override buy/sell prices), `info` (inspect item metadata), and `reload` (rescan without restart)
- **Runtime Discovery Listeners**: Automatically detects new custom items when players obtain them, with configurable rate limiting to avoid main-thread lag
- **Persistent Custom Item Database**: Custom items are saved to a dedicated `custom_items` table with schema versioning and migration support
- **Configurable Scan Intervals**: Operators can tune startup scan delay, periodic rescan frequency, and runtime detection sensitivity via `config.yml`

### Stability
- **Economy Thread Safety**: `getBalance()` cache now uses `ConcurrentHashMap` to prevent race conditions on multi-core servers
- **MySQL Query Dialect Fix**: Replaced deprecated `VALUES(col)` syntax with `AS new` alias syntax for MySQL 8.0.20+ forward compatibility
- **DatabaseManager Error Propagation**: `createTables()` now properly throws SQLExceptions instead of swallowing them, preventing silent schema mismatches
- **Auction Display Name Fix**: Custom-named auction items now show their proper display name instead of raw material type in all messages
- **CloudSync Retry Logic**: Cloud dashboard registration now only retries on transient errors (5xx/network), stopping immediately on permanent HTTP 4xx failures

### Security
- **Web Session Management**: `WebSessionManager` now requires a `JavaPlugin` instance for scheduled cleanup tasks, preventing token leaks from uncleaned expired sessions
- **Session Invalidation on Quit**: Player web sessions are invalidated on disconnect to prevent session hijacking
- **Cryptographically Secure Tokens**: Session tokens use `SecureRandom` instead of `UUID.randomUUID()` for stronger entropy

### Testing
- All 9 CI workflows passing (Build, Mineflayer In-Game, Smoke Test, MySQL Test, Scanner MySQL, Custom Item Detection, Custom Items Persistence, Deduplication, and Security Tests)
- 96/96 Mineflayer in-game tests passing
- Zero duplicate `canonical_id` values in custom_items table on fresh MySQL databases

### Platform
- Targets **Paper 26.1+** (Java 25, `api-version: '26.1'`)
- CI validated against Paper 26.1.2 build 64
- For Paper 1.21.x support, see the `compat/paper-1.21` branch

## v1.4.5 - MySQL Compatibility & Auction Display Names

**Critical fix for MySQL 8.0.20+ servers and custom-named auction items.**

### Fixes
- **MySQL 8.0.20+ Compatibility**: Replaced deprecated `VALUES(col)` syntax with modern `AS new` alias syntax in all upsert queries. MySQL 8.0.20+ deprecates `VALUES(col)` and it will be removed in a future release — this update ensures forward compatibility.
- **PreparedStatement Param Mismatch**: MySQL upserts now only set the parameters they actually use. Previously, the extra SQLite-only 4th parameter was set unconditionally, which was harmless but messy.
- **Auction Custom Display Names**: Auction messages (outbid, new offer, offer accepted, offline earning log) now show custom item display names instead of raw material types. A renamed Iron Helmet will now show its custom name, not "IRON_HELMET". Uses `PlainTextComponentSerializer` for safe Component handling — no more `ClassCastException` risk from casting to `TextComponent`.

### Testing
- Added expanded MySQL CI test suite (10 tests) running against MySQL 8.0 service container
- All 4 upsert code paths exercised: `deposit()`, `setBalance()`, `loadBalance()`, `updatePlayerMetadata()`
- Zero `SQLSyntaxErrorException` confirmed on MySQL 8.0
- Added `isMySQL()` method to `DatabaseManager` for clean dialect detection

### Platform
- Targets **Paper 26.1+** (Java 25, `api-version: '26.1'`)
- Uses Paper's `RegistryAccess` / `RegistryKey` API for enchantment lookups
- CI tested against Paper 26.1.2 build 61
- For Paper 1.21.x support, see the `compat/paper-1.21` branch

# Aurelium - Patch Notes

## v1.4.5 - MySQL Compatibility, Auction Display Names & Paper 1.21.x Support

**Critical fix for MySQL 8.0.20+ servers, custom-named auction items, and now available for Paper 1.21.x.**

### Fixes
- **MySQL 8.0.20+ Compatibility**: Replaced deprecated `VALUES(col)` syntax with modern `AS new` alias syntax in all upsert queries. MySQL 8.0.20+ deprecates `VALUES(col)` and it will be removed in a future release — this update ensures forward compatibility.
- **PreparedStatement Param Mismatch**: MySQL upserts now only set the parameters they actually use. Previously, the extra SQLite-only 4th parameter was set unconditionally, which was harmless but messy.
- **Auction Custom Display Names**: Auction messages (outbid, new offer, offer accepted, offline earning log) now show custom item display names instead of raw material types. A renamed Iron Helmet will now show its custom name, not "IRON_HELMET". Uses `PlainTextComponentSerializer` for safe Component handling — no more `ClassCastException` risk from casting to `TextComponent`.

### Paper 1.21.x Backport (`compat/paper-1.21` branch)
- **Java 21** instead of Java 25 (toolchain and `options.release` updated)
- **Paper API `1.21.4-R0.1-SNAPSHOT`** instead of `26.1.2.build.53-stable`
- **`api-version: '1.21'`** in plugin.yml instead of `'26.1'`
- **Enchantment lookup**: Uses `Registry.ENCHANTMENT.get(NamespacedKey.minecraft(...))` (Bukkit registry) instead of Paper's `RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)` which is 26.1-only
- **Sweeping Edge**: Keeps original Bukkit name `SWEEPING_EDGE` (the `SWEEPING_COLLISION` rename only exists in MC 26.1+)
- CI tested against Paper 1.21.4 build 232 (build, smoke-test, ingame-test, mysql-test all pass)

### Paper 26.1+ Branch (`fix/mysql-compat-and-auction-displayname`)
- Targets **Paper 26.1+** (Java 25, `api-version: '26.1'`)
- Uses Paper's `RegistryAccess` / `RegistryKey` API for enchantment lookups
- CI tested against Paper 26.1.2 build 61

### Testing (both branches)
- All 4 CI jobs pass: build, smoke-test, ingame-test, mysql-test
- Added expanded MySQL CI test suite (10 tests) running against MySQL 8.0 service container
- All 4 upsert code paths exercised: `deposit()`, `setBalance()`, `loadBalance()`, `updatePlayerMetadata()`
- Zero `SQLSyntaxErrorException` confirmed on MySQL 8.0
- Added `isMySQL()` method to `DatabaseManager` for clean dialect detection
- RCON-based in-game command testing (25 tests covering all commands)

## v1.4.3 - CI & Testing Infrastructure

**Automated in-game testing ensures every command works correctly.**

### Testing
- Added RCON-based in-game command testing to GitHub Actions CI (25 tests covering all commands)
- `/bal` variants: self, other player, with currency — all verified
- `/eco` admin commands: give, take, set, with currency, invalid inputs (negative, non-numeric, missing args, invalid currency, invalid action)
- Player-only commands reject console correctly: `/pay`, `/market`, `/web`, `/stocks`, `/ah` (4 subcommands), `/orders` (4 subcommands)
- All tests pass on every push — zero regressions guaranteed

### Internal
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
- Bumped version in `pom.xml` to `1.4.2` to ensure consistent builds across Maven and Gradle.
- Updated `README.md` to clarify that MySQL is mandatory for cross-server synchronization and that Market Prices remain per-server for regional economy support.

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
- `/bal` was checking the wrong permission — non-op players couldn't use it
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

## v1.3.2 - Web Dashboard Polish & Security

### Cloud Dashboard Improvements
- **Fix**: Stock Change % — Resolved a bug where item base prices (like Diamonds) were being overwritten, causing 0% change to show on the web.
- **Fix**: Auction Display — Fixed a bug where auction prices would show as `undefined` instead of the correct currency symbol.
- **New**: Stitch-Inspired Icons — Replaced all legacy emojis with a premium SVG icon system for better clarity and aesthetics.
- **Security**: Self-Trade Protection — Players can no longer bid on their own auctions or fill their own buy orders via the web.
- **New**: Added `sellerUuid` and `buyerUuid` to sync payloads for improved identity tracking on the frontend.

### In-Game Fixes
- **Fix**: GUI De-duplication — Removed redundant item entries in the `/stocks` GUI that appeared if an item was in multiple categories.

## v1.3.1 - Request Limit Update

- **Updated**: Increased the web dashboard API rate limit to 330 requests per minute.
- **New**: Added an automatic rate-limit bypass for authenticated Minecraft servers.

## v1.3.0 - Cloud Dashboard Expansion

### Cloud Dashboard - New Pages
- **New**: Navigation Bar with tabs for Market, Auction, Orders, and Stocks.
- **New**: Auction House page — view all active auctions with item icons, BIN/BID tags, countdown timers, seller names, and search.
- **New**: Buy Orders page — view all active buy orders with progress bars, price per piece, buyer name, and status badges.
- **New**: Stocks / Price Tracker page — view all items with buy price, sell price, and change % (green up / red down). Sortable by name, price, or change.
- **New**: Interactive Stock Charts — click any item to open a chart modal with smooth bezier curves, gradient fill, hover tooltips.
- **New**: Price History Recording — item prices are recorded every 10 minutes and stored for 7 days.
- **New**: `price_history` database table for persistent price tracking.
- **New**: Multi-Version Icon Fallback — gracefully falls back to older version icons if a 1.21.11 icon is missing.
- The Web Dashboard is now fully interactive — players can purchase items, place bids, buyout BIN auctions, and fulfill Buy Orders from their browser.
- **New**: Web Dashboard sessions now use a rolling 1-hour timeout. Timer resets on every interaction.
- **New**: Styled Session Required error screen when visiting without a valid session.
- **New**: Tab Sleep Mode using Page Visibility API. Background sync pauses when tab is hidden, resumes instantly on return.

### GUI Improvements
- **New**: Page Indicator Books in center of navigation bar in both `MarketGUI` and `ShopGUI`.
- **Fix**: Fixed infinite pagination — players can no longer navigate into empty pages.
- **New**: Command Separation — `/market` opens in-game GUI, `/web` opens browser dashboard.

### Cloud Sync Improvements
- **New**: Cross-server dashboard activation queue with fair waitlist.
- **Optimization**: Auction, Order, Stock, and Price History data stored as raw JSON strings to reduce RAM usage.
- Improved timeout handling for Render server cold starts.
- Added retry logic for server registration (5 attempts, 15 seconds apart).
- Increased server JSON request limit to 5MB for larger sync payloads.

### Web Security Hardening
- Fixed XSS vulnerability — HTML escaping now properly sanitizes `<`, `>`, and `&` characters.
- CORS locked down to `https://webaureliummc.onrender.com`.
- Rate limiting — 60 requests per minute per IP.
- Token moved to Authorization header — no longer visible in URLs.
- Security headers (Helmet) — `X-Frame-Options`, `X-Content-Type-Options`, etc.
- IDOR fix — purchase status endpoint verifies requesting player owns the purchase.
- Stale purchase cleanup — pending purchases abandoned for 10+ minutes auto-cleaned.
- Queue cap — registration queue capped at 50 entries.

### Security & Exploits
- **CRITICAL**: Fixed a bug allowing players to bypass transaction costs and duplicate items by interacting with bottom-inventory slots while GUIs were open.
- **CRITICAL**: Fixed a race-condition in `AuctionGUI`'s collection bin granting items twice on rapid clicks.
- Fixed an issue allowing players to drag and lose personal items into empty `GUIHolder` slots.

### Multi-Currency System
- **New**: Server owners can define multiple currencies in `config.yml` with unique symbols and starting balances.
- **New**: Each market item can be assigned a specific currency.
- **New**: `/bal`, `/pay`, and `/eco` commands accept optional `[currency]` argument.
- **New**: `/ah sell` and `/orders create` accept an optional currency argument.
- **New**: `player_balances` database table stores per-player, per-currency balances with automatic migration.
- **Fix**: Web dashboard now correctly displays the exact currency symbol sent by the plugin.
- Vault integration defaults to `economy.default-currency` for backward compatibility.

### GUI Mode Selector
- **New**: `market.gui-mode` config option — `classic` (original chest GUI), `modern` (ShopGUI with MiniMessage), or `web` (browser dashboard).

### Enchanted Books
- **Fix**: CRITICAL bug where purchasing an Enchanted Book would give a blank, unenchanted book. The plugin now correctly parses names like "Protection IV" into actual Bukkit `EnchantmentStorageMeta` drops.

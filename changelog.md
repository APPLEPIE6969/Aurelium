# Aurelium - Changelog

## v1.5.4 - Version Checker & Cloud Dashboard Hardening

### What's New

- **Automatic version check on startup** - Aurelium now checks Modrinth on server startup to see if you are running the latest version for your Minecraft version. If you are behind, it logs a message in the console telling you how many versions behind you are and where to download the latest build. The check runs asynchronously and does not slow down startup.
- **Cloud dashboard key rotation** - when restarting your server with a new API key, the plugin now sends the previous key as proof of ownership, allowing it to re-register automatically without manual intervention. The old key is saved in config for future restarts.
- **Admin endpoint security** - the web dashboard's admin endpoints now require a `REGISTRATION_SECRET` environment variable to be configured. If the secret is not set, the endpoint returns 403 instead of allowing unauthenticated access.
- **Updated to the current stable Paper builds** - Paper 26.2 and 26.1.2 have both shipped builds since the last release. Aurelium is now compiled and tested against the current stable release of each, rather than the alpha builds it was on before. Every supported version gets its own download, so you can pick the one that matches your server.
- **New dashboard address** - the web dashboard is now hosted at `https://aurelium.alwaysdata.net`, replacing the previous address. Configs are migrated automatically the first time your server starts, so there is nothing to change by hand, and the change is logged so you can see it happened.
- **Local dashboard is no longer exposed to the internet** - if you set `mode: local`, the dashboard used to listen on every network interface even though your config said `localhost`. On a publicly reachable Minecraft host that meant anyone who found the port could see it, and the session link travelled unencrypted. It now listens where your config actually says. **If you were reaching your local dashboard from another machine, add `host: "0.0.0.0"` under `web.local` to keep doing that** - the plugin warns you in the console when you do.
- **You can now revoke a dashboard session** - a new `POST /logout` endpoint invalidates your session link immediately instead of leaving it usable until an hour of inactivity passes. Sessions were already cancelled when you disconnect.

### Bug Fixes

- **Fixed a security hole in the dashboard's session system** - the endpoints that create and update player sessions, and the one that confirms a purchase, could be called without a valid server key. Someone who knew or guessed a server ID could have created a session for it and read player balances, or confirmed purchases. All three now require the server's API key and reject anything else.
- **Dashboard buttons work again** - the security rules added for this release initially blocked the dashboard's buttons, confirmations and modals from responding. They all work again, and nothing about the page looks different.
- **Google Analytics removed** - it was loading third-party code onto a page showing player balances and needed a special exception in the security rules. It only ever had a placeholder ID, so nothing was being tracked. It can be added back properly if you want it.
- **Missing item icons no longer fill the console** - when an item texture failed to load, the fallback was blocked by the security rules. It now works as intended and stops after the last attempt instead of retrying.
- **The price history chart can be opened again** - the little trend sparkline next to each item never opened anything, so the chart was unreachable from the dashboard. Click the sparkline, or tab to it and press Enter, and you get the price history for that item.
- **The price history chart is readable now** - the chart was being drawn at half size, which shrank the axis labels to about 5 pixels tall and made the dates overlap into an unreadable pile. It now draws at its real size, so the labels are legible on any screen. The first and last dates are labelled, and the lowest price no longer sits on top of them.
- **MySQL support is properly verified** - the tests that check a MySQL-backed server were not reading the values back properly, so they could report that data was not being saved when it was. MySQL is now confirmed working on every build.
- **Registration CI test** - added an automated CI job that verifies fresh cloud dashboard registration works correctly with a clean server, catching registration regressions before release.

### Security

- The dashboard is now served with a strict content security policy, `X-Frame-Options`, `nosniff`, no caching of player data, and forced HTTPS. The rules need no exceptions and no upkeep, because the dashboard no longer relies on inline scripts or styles.
- The local dashboard gets the same protections, minus forced HTTPS, which does not apply to plain local HTTP.
- Old dashboard sessions are cleared automatically after a few days instead of being kept indefinitely, so the dashboard stays fast as it accumulates players.

---

## v1.5.3 - Paper 26.2 Support & Web Dashboard Overhaul

### What's New

- **Paper 26.2 support** — works with the latest Paper alpha builds
- **Web dashboard is now persistent** — your market data, auctions, and player sessions survive server restarts (powered by Astra DB)
- **All sensitive data is encrypted** — API keys, player UUIDs, balances, and session tokens are encrypted with AES-256 before being stored in the database. Even if someone gets access to the database, they can't read your players' data
- **Auction quantity selector (web)** — when buying a BIN auction with stacked items (like 64x Diamonds), you can now choose how many you want instead of being forced to buy the whole stack
- **Auction category sidebar (web)** — the auction page now has the same sidebar as the market, with filters for All Listings, BIN Listings, and BID Listings
- **Per-item pricing (web)** — auctions and market items now show the price per item, not just the total

### Bug Fixes

- **Sessions survive restarts** — player web sessions no longer break after the server restarts
- **No more silent data loss** — if the database write fails, you get an error instead of the server pretending everything worked fine
- **Better input validation (web)** — the website now properly rejects invalid amounts and IDs instead of silently accepting them
- **Network errors handled (web)** — temporary database connection issues no longer crash the website
- **Auction empty states (web)** — filtering by category now shows "No auctions in this category" instead of showing all auctions
- **Smooth modal animations (web)** — closing bid/purchase popups now animates smoothly instead of vanishing instantly
- **Quantity button states (web)** — the + button grays out when you hit the max, the - button grays out at 1
- **Correct BIN label (web)** — buy-now listings show "Price (per item)" instead of "Your Bid (per item)"
- **Better item icons (web)** — fallback chain tries multiple Minecraft versions before showing a generic box icon

---

## v1.5.2 - Custom Item Display Name Fix

### Bug Fixes

- Custom items now show their proper display name in the market instead of the raw material name

---

## v1.5.1 - Cloud Dashboard URL Fix

### Bug Fixes

- Fixed the cloud dashboard sometimes not registering properly

---

## v1.5.0 - Custom Item Scanner

### What's New

- **Auto-detects custom items** — Aurelium now automatically finds custom items from ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, and SX-Item. No manual config needed
- **/customitems command** — manage discovered items:
  - `/customitems scan` — rescan for new items
  - `/customitems list` — see all discovered items
  - `/customitems info <id>` — item details
  - `/customitems reload` — reload config
  - `/customitems toggle <id>` — enable/disable an item
  - `/customitems price <id> <buy> [sell]` — set prices
- **Cross-plugin dedup** — if the same item exists in multiple plugins, it only shows up once
- **Config overrides** — discovered items are saved to config.yml so you can edit prices and they persist across restarts

### Bug Fixes

- Fixed database schema issues on fresh MySQL installs
- Fixed race conditions in custom item scanning
- Fixed price clamping bugs
- Fixed auction display names for custom items
- Fixed MySQL 8.0 compatibility

---

## v1.4.5 - MySQL & Auction House Fixes

### Bug Fixes

- Fixed MySQL 8.0.20+ compatibility
- Auction messages now show custom item display names

---

## v1.4.3 - CI & Testing Infrastructure

- Added automated in-game testing to ensure all commands work correctly

---

## v1.4.2 - Security & Performance Hardening

### What's New

- Session tokens now use cryptographically secure random generation

### Bug Fixes

- Fixed BungeeCord/Velocity balance sync
- Fixed performance issues with cloud sync and database cleanup

---

## v1.4.1 - Web Stability Hotfix

### Bug Fixes

- Cloud sync now automatically reconnects if the web server restarts

---

## v1.4.0 - Security, Enchantments & Cleanup

### What's New

- Enchantment books have individual prices based on rarity and level
- Added new enchantments: Breach, Density, Wind Burst
- Web dashboard balance updates instantly after in-game transactions

### Bug Fixes

- Various market, auction, and sell GUI fixes
- Economy transactions are now atomic (no duping possible)
- Web purchases are deduplicated
- All GUIs lock shift-click and drag to prevent exploits

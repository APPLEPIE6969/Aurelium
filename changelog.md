# Aurelium - Changelog

## v1.5.4 - Version Checker

### What's New

- **Automatic version check on startup** — Aurelium now checks Modrinth on server startup to see if you are running the latest version for your Minecraft version. If you are behind, it logs a message in the console telling you how many versions behind you are and where to download the latest build. The check runs asynchronously and does not slow down startup.

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

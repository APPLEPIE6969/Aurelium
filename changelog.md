# Aurelium - Changelog

## v1.5.4 - Version Checker & Cloud Dashboard Hardening

### What's New

- **Aurelium tells you when a newer version is out** - when your server starts, it checks Modrinth to see whether there is a newer build for your Minecraft version. If there is, your console tells you how many versions behind you are and where to get the update. It does this quietly in the background, so it never slows your startup.
- **Your dashboard reconnects by itself if you change its API key** - if you ever need to replace your server's API key, Aurelium now recognises the server and reconnects on its own. You no longer have to sign in and register it again by hand.
- **Dashboard admin commands are now locked** - the commands that manage servers now only work if you set a password first. You set it as `REGISTRATION_SECRET` in your host's environment settings, the same place you would put any other secret. If you never set one, the dashboard turns those commands away instead of leaving them open. **If you use admin commands on the dashboard, set this before you upgrade.**
- **Built against the latest stable Paper** - Paper has shipped newer stable builds for 26.2 and 26.1.2. Aurelium is now built and tested against those instead of the earlier pre-release builds. Each Minecraft version still has its own download, so you pick the one matching your server.
- **The dashboard has a new home** - the hosted dashboard now runs at `https://aurelium.alwaysdata.net`. Your config updates itself the first time the server starts after upgrading, and the console confirms it happened. There is nothing to change by hand.
- **The local dashboard no longer opens itself to the internet** - if you set `mode: local`, the dashboard used to listen on every network connection your server had, even when your config said `localhost`. On a publicly reachable host that meant anyone who found the port could see it, and the link players use travelled unencrypted. It now listens only where your config tells it to. **If you open your local dashboard from a different machine, add `host: "0.0.0.0"` under `web.local` to keep doing that** - the plugin warns you in the console when you use a wildcard like this.
- **You can end a dashboard session straight away** - a player you want to disconnect can have their dashboard link cancelled immediately from the console, rather than waiting for it to time out on its own. Sessions already ended automatically when a player left the server.

### Bug Fixes

- **Spawners can be bought again** - every spawner was showing a price of 0, which meant they could not be bought at all. They appeared worthless on the dashboard too, and their price history charts came out as a flat line, which is why those charts looked empty. They now show and charge their real price. **You do not need to change your config**, the prices you already set are used as they are.
- **Your items show up on the dashboard again** - the dashboard was showing no items at all for many players. Two separate problems were causing it. One quietly threw away anything bigger than a couple of megabytes, and a normal set of items is about three, so it was being dropped every time. The other was worse: a routine background check was overwriting your real data with an empty update every thirty seconds. Both are fixed, and the dashboard now shows your actual items.
- **Purchases finish straight away** - buying something from the dashboard always waited about twenty seconds and then told you it had timed out, even when the purchase had actually gone through and the items were delivered. The dashboard was asking the server a question the server had no way to answer. It can answer now, so purchases confirm as soon as they land and you are no longer left wondering whether they worked.
- **The price history chart shows you values** - move your mouse across the chart and a line follows it, with a dot marking the spot and the price and time shown for that point.
- **The price history chart fits on the screen** - the window was too narrow to read the chart properly. It now opens much wider, and fades closed like the other popups instead of vanishing.
- **The currency badge is gone from market cards** - it repeated information already shown on the card and made the prices harder to line up.
- **A security hole in dashboard links is closed** - the parts of the dashboard that create and update a player's link, and the part that confirms a purchase, could be reached without a valid key. Anyone who knew or guessed a server ID could have made themselves a link and read player balances, or confirmed purchases. All of it now requires your server's own key and turns away anything else.
- **Dashboard buttons work again** - the security improvements in this release initially stopped the dashboard's buttons, confirmation boxes and popups from responding. They all work again, and nothing about how the page looks has changed.
- **Google Analytics removed** - it was loading outside code onto a page that shows player balances, and nothing was ever being tracked with it, since it only had a placeholder ID. It can be added back properly if you want it later.
- **Missing item icons no longer flood your console** - when an item picture could not be found, the plugin kept retrying and filled the console with repeated warnings. It now tries each fallback once and stops.
- **The price history chart can be opened again** - the little trend line next to each item did nothing when clicked, so the chart could not be reached from the dashboard at all. Click it, or tab to it and press Enter, and you get the price history for that item.
- **The price history chart is readable now** - the chart was being drawn at half the size it was displayed at, which shrank the date labels down to about 5 pixels tall and piled them on top of each other. It now draws at full size, so the dates are legible on any screen. The first and last dates are labelled, and the lowest price no longer sits on top of them.
- **MySQL is properly tested now** - the checks that were supposed to confirm a MySQL database was saving correctly were not actually reading the data back, so they could pass or fail for the wrong reason. Your database setup is now genuinely confirmed working on every release.

### Security

- The dashboard is now much harder to attack. It is served over HTTPS only, refuses to be embedded in other websites, tells browsers not to guess its file types, and does not keep player information in page caches. None of this needs maintaining by hand, because the dashboard no longer relies on scripts or styling written directly into the page.
- The local dashboard gets the same protections. It keeps working over plain local connections, since that never leaves your machine.
- Dashboard links from players you have not seen in a few days are now cleared automatically instead of being kept forever, so the dashboard stays fast as more players use it.

## v1.5.3 - Paper 26.2 Support & Web Dashboard Overhaul

### What's New

- **Paper 26.2 support** - works with the latest Paper alpha builds
- **Web dashboard is now persistent** - your market data, auctions, and player sessions survive server restarts (powered by Astra DB)
- **All sensitive data is encrypted** - API keys, player UUIDs, balances, and session tokens are encrypted with AES-256 before being stored in the database. Even if someone gets access to the database, they can't read your players' data
- **Auction quantity selector (web)** - when buying a BIN auction with stacked items (like 64x Diamonds), you can now choose how many you want instead of being forced to buy the whole stack
- **Auction category sidebar (web)** - the auction page now has the same sidebar as the market, with filters for All Listings, BIN Listings, and BID Listings
- **Per-item pricing (web)** - auctions and market items now show the price per item, not just the total

### Bug Fixes

- **Sessions survive restarts** - player web sessions no longer break after the server restarts
- **No more silent data loss** - if the database write fails, you get an error instead of the server pretending everything worked fine
- **Better input validation (web)** - the website now properly rejects invalid amounts and IDs instead of silently accepting them
- **Network errors handled (web)** - temporary database connection issues no longer crash the website
- **Auction empty states (web)** - filtering by category now shows "No auctions in this category" instead of showing all auctions
- **Smooth modal animations (web)** - closing bid/purchase popups now animates smoothly instead of vanishing instantly
- **Quantity button states (web)** - the + button grays out when you hit the max, the - button grays out at 1
- **Correct BIN label (web)** - buy-now listings show "Price (per item)" instead of "Your Bid (per item)"
- **Better item icons (web)** - fallback chain tries multiple Minecraft versions before showing a generic box icon

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

- **Auto-detects custom items** - Aurelium now automatically finds custom items from ItemsAdder, Oraxen, MMOItems, MythicMobs, ExecutableItems, Nexo, and SX-Item. No manual config needed
- **/customitems command** - manage discovered items:
  - `/customitems scan` - rescan for new items
  - `/customitems list` - see all discovered items
  - `/customitems info <id>` - item details
  - `/customitems reload` - reload config
  - `/customitems toggle <id>` - enable/disable an item
  - `/customitems price <id> <buy> [sell]` - set prices
- **Cross-plugin dedup** - if the same item exists in multiple plugins, it only shows up once
- **Config overrides** - discovered items are saved to config.yml so you can edit prices and they persist across restarts

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

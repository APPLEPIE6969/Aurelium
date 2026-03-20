# AurelEconomy - Patch Notes

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
- Moved Beacon, Respawn Anchor, End Crystal back to Mob Drops & Magic
- Web dashboard scroll performance improved with lazy rendering
- Better error messages for network issues during cloud sync


## Version 1.3.2 — Web Dashboard Polish & Security (Minecraft 1.21.11)

### 🌐 Cloud Dashboard Improvements
* **Fix**: **Stock Change % Fix** — Resolved a bug where item base prices (like Diamonds) were being overwritten, causing 0% change to show on the web.
* **Fix**: **Auction Display Fix** — Fixed a bug where auction prices would show as `undefined` instead of the correct currency symbol.
* **New**: **Stitch-Inspired Icons** — Replaced all legacy emojis with a premium SVG icon system for better clarity and aesthetics.
* **Security**: **Self-Trade Protection** — Players can no longer bid on their own auctions or fill their own buy orders via the web.
  - Buy buttons are now explicitly labeled "Your Auction/Order" and disabled for owned items.
  - Added backend validation to reject self-trading attempts.
* **New**: Added `sellerUuid` and `buyerUuid` to sync payloads for improved identity tracking on the frontend.

### 🎮 In-Game Fixes
* **Fix**: **GUI De-duplication** — Removed redundant item entries in the `/stocks` GUI that appeared if an item was in multiple categories.

## Version 1.3.1 — Request Limit Update (Minecraft 1.21.11)

* **Updated**: Increased the web dashboard API rate limit to **330 requests per minute**.
* **New**: Added an automatic rate-limit bypass for authenticated Minecraft servers.

## Version 1.3.0 — Cloud Dashboard Expansion (Minecraft 1.21.11)

### 🌐 Cloud Dashboard — New Pages
* **New**: Added **Navigation Bar** to the web dashboard with tabs for Market, Auction, Orders, and Stocks.
* **New**: **Auction House** page — view all active auctions with item icons, BIN/BID tags, countdown timers, seller names, and search.
* **New**: **Buy Orders** page — view all active buy orders with progress bars (filled/requested), price per piece, buyer name, and status badges.
* **New**: **Stocks / Price Tracker** page — view all items with buy price, sell price, and change % (green ↑ / red ↓). Sortable by name, price, or change.
* **New**: **Interactive Stock Charts** — click any item on the Stocks page to open a Modrinth-inspired chart modal with:
  - Smooth bezier curve lines with gradient fill (green for positive trend, red for negative)
  - Y-axis price labels and X-axis date labels
  - Hover tooltips showing exact date, buy price, and sell price
* **New**: **Price History Recording** — item prices are recorded every 10 minutes and stored for 7 days.
* **New**: `price_history` database table for persistent price tracking.
* **New**: **Multi-Version Icon Fallback:** The dashboard will gracefully fallback to older version icons (1.20, 1.19, 1.18) if a 1.21.11 icon is missing from the API, preventing broken images.
* **The Web Dashboard is now fully interactive!** Players can now purchase items from the Server Market, place bids on the Auction House, buyout BIN auctions, and fulfill Buy Orders straight from their browser.
  * *Note: To fulfill orders or buy/bid on auctions from the web, players must have the required funds/items currently in their online inventory.*
* **New**: Web Dashboard sessions now use a **rolling 1-hour timeout**. The timer resets every time you interact with the dashboard, so active users are never kicked out. Sessions only expire after 1 full hour of inactivity.
* **New**: A styled **🔒 Session Required** error screen now appears when visiting the dashboard without a valid session, guiding users to issue `/web` in-game.
* **New**: Added **Tab Sleep Mode** using the browser's Page Visibility API. If a player switches to another tab or minimizes the browser, the 20-second background data sync pauses to save data and RAM. It instantly fetches fresh data the moment they return to the dashboard.

### 🎮 GUI Improvements
* **New**: Added **Page Indicator Books** to the center of the navigation bar in both `MarketGUI` and `ShopGUI`.
* **Fix**: Fixed a bug where pagination was infinite; players can no longer navigate into empty pages.
* **New**: **Command Separation** — `/market` now strictly opens the in-game GUI (Classic or Modern). The browser dashboard is now exclusively accessed via the `/web` command.

### 🖥️ Cloud Sync Improvements
* **New**: Cross-server dashboard activation queue. To prevent crashes, the global Node.js backend (`render-server`) now monitors memory usage (`>= 500MB`). If a server tries to activate its dashboard when memory is maxed out, it will be placed in a fair waitlist queue.
* **Optimization**: Auction, Order, Stock, and Price History data are now stored as raw JSON strings instead of parsed JavaScript objects, drastically reducing RAM usage per server.
* Improved timeout handling for Render server cold starts (60s connect timeout, per-request timeouts).
* Added retry logic for server registration (5 attempts, 15 seconds apart).
* Sync payload now includes auction, order, stock, and price history data.
* Increased server JSON request limit to 5MB for larger sync payloads.

### 🛡️ Web Security Hardening
* **Fixed XSS vulnerability** — the frontend HTML escaping function now properly sanitizes `<`, `>`, and `&` characters, preventing malicious script injection via crafted item or player names.
* **CORS locked down** — API now only accepts requests from `https://webaureliummc.onrender.com`, blocking malicious third-party websites.
* **Rate limiting** — API endpoints now enforce 60 requests per minute per IP to prevent spam and DDoS.
* **Token moved to Authorization header** — session tokens are no longer visible in URLs, preventing leaks via browser history, server logs, or screenshots.
* **Security headers (Helmet)** — added `X-Frame-Options`, `X-Content-Type-Options`, and other standard HTTP security headers to prevent clickjacking and MIME sniffing.
* **IDOR fix** — purchase status endpoint now verifies that the requesting player owns the purchase, preventing information leakage.
* **Stale purchase cleanup** — pending purchases abandoned for 10+ minutes are now automatically cleaned up.
* **Queue cap** — registration queue capped at 50 entries to prevent abuse.

### 🔒 Security & Exploits
* **CRITICAL**: Fixed a major bug that allowed players to bypass transaction costs and duplicate items by interacting with their personal bottom-inventory slots while `MarketGUI`, `AuctionGUI`, `BidGUI`, `OffersGUI`, or `ConfirmPurchaseGUI` were open.
* **CRITICAL**: Fixed a race-condition in `AuctionGUI`'s collection bin that would occasionally grant an item twice if clicked extremely fast via macros or due to server lag.
* Fixed an issue allowing players to drag and lose personal items into empty `GUIHolder` slots.

### 💱 Multi-Currency System
* **New**: Server owners can now define multiple currencies in `config.yml` (e.g., Aurels, Dollars, Euros) with unique symbols and starting balances.
* **New**: Each market item can be assigned a specific currency via `market.items.<ITEM>.currency`.
* **New**: `/bal`, `/pay`, and `/eco` commands now accept an optional `[currency]` argument.
* **New**: `/ah sell` and `/orders create` accept an optional currency argument — buyers pay in the seller's specified currency.
* **New**: `player_balances` database table stores per-player, per-currency balances with automatic migration from legacy single-balance data.
* **Fix**: The web dashboard now correctly displays the *exact currency symbol* (e.g. `₳` or `$`) sent by the plugin, instead of hardcoded text.
* Vault integration defaults to `economy.default-currency` for backward compatibility.

### 🖥️ GUI Mode Selector
* **New**: Added `market.gui-mode` config option — server owners choose between three market interfaces:
  - `classic` — Original chest-based `MarketGUI`.
  - `modern` — New `ShopGUI` with MiniMessage gradient titles, glass-pane borders, and styled lore.
  - `web` — Opens a browser-based dashboard (see below).

### 🌐 Web Dashboard
* **New**: Embedded Modrinth-inspired web dashboard served by the plugin's built-in HTTP server (zero external dependencies).
* **New**: `/web` command generates a secure, time-limited clickable link that opens the market in the player's browser.
* Dark-themed UI with category sidebar, item card grid, real-time search, buy modal with amount selector, and toast notifications.
* Session tokens use a rolling 1-hour timeout (hardcoded for security).
* All purchases are executed on the main server thread for thread-safety.
* Configuration:
  web:
    enabled: false
    port: 8585
    # Session timeout: rolling 1 hour of inactivity (hardcoded)
  ```

### 📚 Enchanted Books
* **Fix**: **CRITICAL** bug where purchasing an Enchanted Book from the MarketGUI or ShopGUI would give the player a completely blank, unenchanted book. The plugin now perfectly parses internal names (like "Protection IV") into actual Bukkit `EnchantmentStorageMeta` drops!

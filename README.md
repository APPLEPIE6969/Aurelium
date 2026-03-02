# Aurelium 💎

**Aurelium** is a comprehensive, standalone economy plugin for Minecraft Paper 1.21.11. It features a custom currency (**Aurels**), a flexible Admin Market, a player-driven Auction House, and seamless Vault integration.

## ✨ Features

### 💰 Economy
- **Currency**: "Aurels" (₳) - Customizable name and symbol.
- **Vault Support**: Fully compatible with Vault-dependent plugins (ShopGUI+, Essentials, etc.).
- **Database**: Robust SQLite storage (no setup required).
- **Offline Earnings**: Get paid for auction sales even when you're offline.

### 🏪 Market
A server-owned shop that functions like a **Stock Market**:
- **Demand/Supply Logic**: Prices automatically rise when people buy (Demand) and fall when they sell (Supply). Both Buy and Sell values scale proportionally.
- **Anti-Exploit Safeguards**: Mathematically prevents the dynamic Sell price from ever exceeding the Buy price.
- **Live UI Updates**: The `/market` and `/stocks` menus automatically refresh every **1 second** natively while open.
- **Searchable Menus**: Easily find any item in the Market or Auction House using the new **Compass** search button.
- **Market Crash Alerts**: Server-wide announcements when high-value items (Diamonds, Spawners, etc.) drop to bargain prices.
- **Performance Optimized**: 
    - **Viewer-Only Refresh**: Logic remains completely dormant unless a player has a menu open.
    - **Batched Disk I/O**: Transaction data is saved in backgrounds batches, preventing server "lag spikes" during high-volume trading.
    - **O(1) Data Access**: Market prices use ultra-fast local caching for near-zero CPU impact.
- **Smart Inventory Logistics**: Purchasing items securely fills your existing partial stacks instead of strictly demanding empty inventory slots.
- **Massive Catalog**: Includes **ALL Building Blocks** (Stones, Deepslate, Wood, Glass, Nature, etc.) and over **60+ Mob Spawners**. 

### ⚖️ Auction
A fully self-contained player-driven exchange:
- **100% GUI Driven**: Complex chat commands are a thing of the past. You can effortlessly **Sell Items**, **Place Custom Bids**, and **Make Private Offers** directly via intuitive chat-prompt buttons inside the menus.
- **Safety Confirmations**: Never accidentally bankrupt yourself again. Both *Buy It Now* and *Bidding* feature a dedicated **Confirmation Screen** before deducting funds.
- **Manage Offers**: Sellers can view all incoming deals in `/ah offers` and instantly **Accept (✔)** or **Decline (✖)**.
- **Live Updates**: The GUI **instantly refreshes** for all players when an item is bought, bidded on, or cancelled. Additionally, **auction timers update in real-time** every second while the menu is open.
- **Auto-Refunds**: Placed a bid and got outbid? Your money is **instantly returned** to your balance.
- **Pro-Tip**: Almost every command below (like `/ah search`, `/sell`, and `/ah offer`) can also be triggered by simply **clicking the intuitive buttons** (Compass, Emerald, or Books) directly inside the GUIs!

### 🛒 Buy Orders
A global request system that lets players buy things they want even while offline:
- **Global Requests**: Request any item in the game (including all 120+ Enchanted Books and Ingots) at your specific custom price.
- **Automated Fulfillment**: Other players can browse active orders and fulfill them instantly directly from their inventory.
- **Escrow System**: Money is held safely in escrow. If you cancel an order, your unspent funds are instantly returned.
- **Offline Collections**: Items fulfilled while you are offline are safely put into your Auction House `/ah collect` bin. You'll be notified on login!
- **Buyer Notifications**: Get notified in real-time when someone fills your order, or on your next login if you were offline.
- **Intelligent Searching**: The `/orders` GUI features dynamic search buttons (Compass and Sign icons) allowing you to filter specific categories or search the entire Minecraft catalog for any valid item.

### 📊 Market Stabilization
- **Price Floor & Ceiling**: Prices can never crash below 20% or inflate above 500% of the original base value (both configurable).
- **Natural Recovery**: Prices passively drift back toward their base value every 10 minutes, preventing permanent crashes from auto-farms.

### 📈 Stocks
- **Real-time Tracking**: View the incredibly accurate *Current Buy Price* and *Current Sell Price* of every item in the market.
- **Trends**:
    - **Green (▲ +%)**: Demand is peaking.
    - **Red (▼ -%)**: Market is oversaturated.

## Commands

### Player

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/bal [player]` | Check your own or another player's balance. | `aureleconomy.bal` |
| `/pay <player> <amount>` | Pay another player. | `aureleconomy.pay` |
| `/market` | Open the server market. | `aureleconomy.market` |
| `/stocks` | View market trends. | `aureleconomy.stocks` |
| `/orders` | Open the Buy Orders GUI. | `aureleconomy.orders` |
| `/orders create <item> <amount> <price>` | Place a buy order via command. | `aureleconomy.orders` |
| `/orders fill <id> [amount]` | Fulfill someone else's order from your inventory. | `aureleconomy.orders` |
| `/orders cancel <id>` | Cancel your own order and get a refund. | `aureleconomy.orders` |
| `/orders my` | List all your active buy orders with IDs. | `aureleconomy.orders` |
| `/orders search <query>` | Search active orders by item name. | `aureleconomy.orders` |
| `/ah` | Open the Auction House. | `aureleconomy.ah` |
| `/ah sell <price> [duration]` | List item for BIN (Buy It Now). Duration optional (e.g. 1h, 7d). | `aureleconomy.ah` |
| `/ah bid <price> [duration]` | List item for Auction. Duration optional (e.g. 1h, 7d).| `aureleconomy.ah` |
| `/ah offer <id> <qty>` | Send a private offer for an item. | `aureleconomy.ah` |
| `/ah offers` | Manage incoming private offers. | `aureleconomy.ah` |
| `/ah collect` | Collect expired/purchased items. | `aureleconomy.ah` |
| `/ah search <query>` | Instantly open a filtered view of the Auction House. | `aureleconomy.ah` |


### Admin

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/eco <give/take/set> <player> <amount>` | Modify player balances. | `aureleconomy.admin` |

## ⚙️ Setup

1.  Download `Aurelium-1.2.0.jar`.
2.  Place it in your server's `plugins/` folder.
3.  **Restart** the server.
    - *Note: If Vault is not detected, Aurelium will automatically extract and install it into your plugins folder for you upon first run.*

## 🔧 Config

### Items
Control every price directly in the config:

```yaml
market:
  items:
    DIAMOND:
      buy: 5000.0    # Cost to buy from server
      sell: 0.0      # 0.0 = Selling DISABLED
    DIRT:
      buy: 1.0
      sell: 0.5      # Players can sell dirt for 0.5
    BEDROCK:
      buy: -1.0      # -1.0 = Buying DISABLED
```

### Global
Control the plugin's behavior in `config.yml`:

```yaml
economy:
  currency-name: "Aurels"           # Full currency name
  currency-symbol: "₳"              # Symbol for prices
  starting-balance: 100.0           # New player balance
  max-balance: -1                   # Max balance (-1 = unlimited)
  min-pay-amount: 0.01              # Minimum /pay transaction

market:
  enabled: true                     # Master toggle for /market
  dynamic-pricing: true             # Prices move on buy/sell
  price-increase-per-buy: 0.001     # +0.1% per buy
  price-decrease-per-sell: 0.001    # -0.1% per sell
  default-sell-ratio: 0.5           # New items default sell = 50% of buy
  price-floor: 0.2                  # Can't drop below 20% of base
  price-ceiling: 5.0                # Can't rise above 500% of base
  price-recovery:
    enabled: true                   # Passive drift toward base
    rate: 0.01                      # 1% of gap per cycle
    interval-minutes: 10            # Recovery cycle frequency

auction-house:
  enabled: true                     # Master toggle for /ah
  default-duration: 86400           # 24 hours (seconds)
  max-duration: 604800              # 7 days max
  listing-fee-percent: 2.0          # Fee to list
  sales-tax-percent: 5.0            # Tax on sale
  max-listings-per-player: -1       # -1 = unlimited
  min-listing-price: 1.0            # Minimum listing price

buy-orders:
  enabled: true                     # Master toggle for /orders
  max-active-orders-per-player: 10  # -1 = unlimited
  min-price-per-piece: 0.1          # Minimum offer price
  max-order-value: -1               # -1 = unlimited
  creation-fee-percent: 2.0         # Order creation fee
  sales-tax-percent: 5.0            # Seller tax on fulfillment
```

### 🌍 Language
A `messages.yml` file is generated on startup.
- **Translate**: Change any message to your language.
- **Color Codes**: Use MiniMessage formatting (e.g., `<green>`) or legacy `&` codes.

## ❓ FAQ
- **"Unknown Command"**: If `/market` or `/eco` says "Unknown command", the plugin failed to load.
    - Check your server console/logs for errors.
    - Ensure you have `Aurelium-1.2.1.jar` in `plugins/`.
    - Ensure you are running **Paper 1.21.x**.
- **"No Permission"**:
    - Ensure you are **OP** (`/op <player>`) or have the permission node `aureleconomy.admin`.
    - Note: Standard player commands (`/bal`, `/market`, `/ah`, `/sell`) are enabled for everyone by default.
- **Still not working?**
    - If none of the above fixes your issue, please [open an issue](https://github.com/APPLEPIE6969/Aurelium/issues) on GitHub with your server version, error logs, and steps to reproduce.

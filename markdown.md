# Aurelium 💎

**Aurelium** is a comprehensive, standalone economy plugin for Minecraft Paper 1.21, but compatible with 1.21.x. It features a custom currency (**Aurels**), a flexible Admin Market, a player-driven Auction House, and seamless Vault integration.

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

1.  Download `Aurelium-1.1.0.jar`.
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
market:
  # 📈 Dynamic Pricing
  # If true, prices rise when items are bought and fall when sold.
  # Set to 'false' for static prices.
  dynamic-pricing: true

  # Price adjustment percentage per transaction (0.001 = 0.1%)
  price-increase-per-buy: 0.001
  price-decrease-per-sell: 0.001

  # Items that cannot be bought or sold
  blacklist:
    - BEDROCK
    - BARRIER

auction-house:
  # ⏱️ Duration (seconds)
  default-duration: 86400 # 24 Hours

  # 💸 Taxes
  # Fee to list an item (Percentage of asking price)
  listing-fee-percent: 2.0
  # Tax taken from the seller when an item sells
  sales-tax-percent: 5.0
```

### 🌍 Language
A `messages.yml` file is generated on startup.
- **Translate**: Change any message to your language.
- **Color Codes**: Use MiniMessage formatting (e.g., `<green>`) or legacy `&` codes.

## ❓ FAQ
- **"Unknown Command"**: If `/market` or `/eco` says "Unknown command", the plugin failed to load.
    - Check your server console/logs for errors.
    - Ensure you have `Aurelium-1.1.0.jar` in `plugins/`.
    - Ensure you are running **Paper 1.21.x**.
- **"No Permission"**:
    - Ensure you are **OP** (`/op <player>`) or have the permission node `aureleconomy.admin`.
    - Note: Standard player commands (`/bal`, `/market`, `/ah`, `/sell`) are enabled for everyone by default.

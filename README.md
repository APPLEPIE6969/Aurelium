# Aurelium 💎

**Aurelium** (formerly AurelEconomy) is a comprehensive, standalone economy plugin for Minecraft Paper 1.21, but is compatible with 1.21.x. It features a custom currency (**Aurels**), a flexible Admin Market, a player-driven Auction House, and seamless Vault integration.

## ✨ Key Features

### 1. 💰 Core Economy
-   **Currency**: "Aurels" (₳) - Customizable name and symbol.
-   **Vault Support**: Fully compatible with Vault-dependent plugins (ShopGUI+, Essentials, etc.).
-   **Database**: Robust SQLite storage (no setup required).
-   **Offline Earnings**: Get paid for auction sales even when you're offline.

### 2. 🏪 Customizable Admin Market (`/market`)
A server-owned shop that functions like a **Stock Market**:
-   **Demand/Supply Logic**: Prices automatically rise when people buy (Demand) and fall when they sell (Supply).
-   **Massive Catalog**: Includes **ALL Building Blocks** (Stones, Deepslate, Wood, Glass, Nature, etc.).
-   **Spawner Market**: Over **60+ Mob Spawners** available for purchase.
-   **Balanced Economy**: Hand-crafted prices based on crafting complexity.
-   **Volatility Visualization**: View real-time market trends in `/stocks`.

### 3. ⚖️ Auction House (`/ah`)
A player-driven exchange with advanced trading tools:
-   **Easy Bidding**: dedicated **Bid Menu** with quick-increment buttons (+10%, +50%, +100%).
-   **Auction IDs**: Every listing now displays a unique **ID** (e.g., `#123`) in the item lore to help with commands.
-   **Private Offers**: Don't like the price? **Right-Click** an item to send a private offer to the seller using its ID.
-   **Manage Offers**: Sellers can view all incoming deals in `/ah offers` and instantly **Accept (✔)** or **Decline (✖)**.
-   **Live Updates**: The GUI **instantly refreshes** for all players when an item is bought, bidded on, or cancelled.
-   **Cancellation**: Sellers can cancel their own auctions with **Shift+Right-Click**. A partial fee refund is given based on remaining time.
-   **Auto-Refunds**: Placed a bid and got outbid? Your money is **instantly returned** to your balance.
-   **Collection Bin**: Expired items or won auctions are held in a secure `/ah collect` bin if your inventory is full.

### 4. 📈 Stocks & Market Trends (`/stocks`)
-   **Real-time Tracking**: View the current value of every item in the market.
-   **Trends**: 
    -   **Green (▲ +%)**: Demand is peaking.
    -   **Red (▼ -%)**: Market is oversaturated.
-   **Precision**: Data points updated per-transaction for maximum accuracy.

## Commands

### Player Commands
| Command | Description | Permission |
| :--- | :--- | :--- |
| `/bal [player]` | Check balance. | `aureleconomy.bal` |
| `/pay <player> <amount>` | Pay another player. | `aureleconomy.pay` |
| `/market` | Open the server market. | `aureleconomy.market` |
| `/stocks` | View market trends. | `aureleconomy.stocks` |
| `/ah` | Open the Auction House. | `aureleconomy.ah` |
| `/ah sell <price>` | List item for BIN (Buy It Now). | `aureleconomy.ah` |
| `/ah bid <price>` | List item for Auction. | `aureleconomy.ah` |
| `/ah offer <id> <qty>` | Send a private offer for an item. | `aureleconomy.ah` |
| `/ah offers` | Manage incoming private offers. | `aureleconomy.ah` |
| `/ah collect` | Collect expired/purchased items. | `aureleconomy.ah` |

### Admin Commands
| Command | Description | Permission |
| :--- | :--- | :--- |
| `/eco <give/take/set> <player> <amount>` | Modify player balances. | `aureleconomy.admin` |

## ⚙️ Installation
1.  Download `Aurelium-1.0.0-SNAPSHOT.jar`.
2.  Place it in your server's `plugins/` folder.
3.  **Restart** the server. (Vault will auto-install if missing).

## 🔧 Configuration (`config.yml`)
### Market Items
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
### Global Settings
Control the plugin's behavior in `config.yml`:

```yaml
market:


  # 📈 Dynamic Pricing
  # If true, prices rise when items are bought and fall when sold. 
  # Set to 'false' for static prices.
  dynamic-pricing: true
  
  # Price adjustment percentage per transaction (0.01 = 1%)
  price-increase-per-buy: 0.01
  price-decrease-per-sell: 0.01
  
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
### 🌍 Localization
A `messages.yml` file is generated on startup.
-   **Translate**: Change any message to your language.
-   **Color Codes**: Use MiniMessage formatting (e.g., `<green>`) or legacy `&` codes.

## ❓ Troubleshooting
-   **"Unknown Command"**: If `/market` or `/eco` says "Unknown command", the plugin failed to load.
    -   Check your server console/logs for errors.
    -   Ensure you have `Aurelium-1.0.0-SNAPSHOT.jar` in `plugins/`.
    -   Ensure you are running **Paper 1.21.x**.
-   **"No Permission"**:
    -   Ensure you are **OP** (`/op <player>`) or have the permission node `aureleconomy.admin`.

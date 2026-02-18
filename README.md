# Aurelium 💎

**Aurelium** (formerly AurelEconomy) is a comprehensive, standalone economy plugin for Minecraft Paper 1.21.x It features a custom currency (**Aurels**), a flexible Admin Market, a player-driven Auction House, and seamless Vault integration.

## ✨ Key Features

### 1. 💰 Core Economy
-   **Currency**: "Aurels" (₳) - Customizable name and symbol.
-   **Vault Support**: Fully compatible with Vault-dependent plugins (ShopGUI+, Essentials, etc.).
-   **Auto-Install**: Automatically detects if Vault is missing and installs it for you on startup!
-   **Database**: Robust SQLite storage (no setup required).

### 2. 🏪 Customizable Admin Market (`/market`)
A server-owned shop that gives you **FULL control**:
-   **Configurable Items**: On first run, **every item** is written to `config.yml`.
-   **Buy/Sell Control**:
    -   Set `buy: -1.0` to **disable buying** an item.
    -   Set `sell: 0.0` to **disable selling** an item.
    -   *Default*: Buying enabled for resources/blocks, selling disabled (Buy-Only Market) unless you enable it!
-   **Dynamic Pricing**: Prices fluctuate based on supply (optional).
-   **Refined Selection**: No junk tools/armor. Focus on blocks, nature, and rare resources.
-   **Expensive Ores**: Luxury pricing for Diamonds (5k) and Ancient Debris (25k).

### 3. ⚖️ Auction House (`/ah`)
A player-to-player trading system:
-   **Offline Sales**: If your item sells while you are offline, you get paid immediately!
    -   **Notification**: On join, you see: *"You earned 15,000₳ for selling Diamond Sword while offline."*
-   **Sell**: List items for sale with `/ah sell <price>`.
-   **Bid & BIN**: Support for Auction bidding and Buy It Now.
-   **Collection Bin**: Expired or cancelled items go to `/ah collect`.

### 4. 🕸️ Spawners
-   **Buy**: Purchase pre-filled Mob Spawners (Zombie, Skeleton, Iron Golem, etc.) from the Market.
-   **Mine**: Using a **Silk Touch** pickaxe allows you to pick up spawners and keep their mob type!
-   **Place**: Placing a specific spawner works correctly (no more default Pig spawners).

## 📜 Commands

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/bal [player]` | Check your or another player's balance. | `None` |
| `/pay <player> <amount>` | Send money to another player. | `None` |
| `/market` | Open the Admin Market GUI. | `None` |
| `/ah` | Open the Auction House GUI. | `None` |
| `/ah sell <price>` | Sell the item in your hand. | `None` |
| `/ah collect` | Collect expired/cancelled items. | `None` |
| `/eco <give/take/set>` | Admin command to modify balances. | `aureleconomy.admin` |

## ⚙️ Installation
1.  Download `Aurelium-1.0.0.jar`.
2.  Place it in your server's `plugins/` folder.
3.  **Restart** the server.
4.  *Note*: If you don't have Vault, the plugin will install it for you. Just restart once more!

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
### Other Settings
-   **Starting Balance**: Default 100.0.
-   **Auction Tax**: Set listing fees and sales tax (default 2% / 5%).
-   **Dynamic Pricing**: Enable/Disable price fluctuation.

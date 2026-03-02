# Aurelium 💎

**Aurelium** (formerly AurelEconomy) is a comprehensive, standalone economy plugin for Minecraft Paper 1.21.11. It features a custom currency (**Aurels**), a flexible Admin Market, a player-driven Auction House, a global Buy Order system, and seamless Vault integration.

## ✨ Key Features

### 1. 💰 Core Economy
-   **Currency**: "Aurels" (₳) - Customizable name and symbol.
-   **Vault Support**: Fully compatible with Vault-dependent plugins (ShopGUI+, Essentials, etc.).
-   **Database**: High-performance SQLite storage with WAL (Write-Ahead Logging) enabled.
-   **Offline Reliability**: Get paid for auction sales or buy order fulfillments even when you're offline.

### 2. 🏪 Dynamic Admin Market (`/market`)
A server-owned shop that functions like a **Stock Market**:
-   **Exponential Volatility**: Prices rise when people buy (Demand) and fall when they sell (Supply) using compound inflation logic.
-   **Massive Catalog**: Includes **ALL Building Blocks**, including every 1.21.11 update item (Pale Garden, Copper variants, new Nature items).
-   **Spawner Market**: Over **60+ Mob Spawners** available for purchase.
-   **Real-time Tracking**: View market trends and sorting-priority volatility in the `/stocks` menu.

### 3. 🛒 Global Buy Orders (`/orders`) [NEW]
Players can now place public requests to purchase items:
-   **Global Requests**: Create a listing to buy specific items at a price you set.
-   **Escrow System**: Funds are held securely until the order is filled or cancelled.
-   **Automated Collection**: Filled orders are delivered to your `/ah collect` bin instantly.
-   **Enchanted Books & Ores**: Place orders for specific Enchantments and Smelted Ores directly.

### 4. ⚖️ Auction House (`/ah`)
A player-driven exchange with advanced trading tools:
-   **Private Offers**: Use **Right-Click** to send custom trade offers to sellers.
-   **Collection Bin**: Secure `/ah collect` storage for won auctions or expired items.
-   **Live Interaction**: GUIs refresh instantly for all players during bids or sales.

### 5. � Market Stabilization
-   **Price Floors & Ceilings**: Prevent items from crashing to zero or inflating to infinity with configurable limits.
-   **Natural Recovery**: Prices passively drift back toward their base value every 10 minutes to maintain economic health.

## Commands

### Player Commands
| Command | Description | Permission |
| :--- | :--- | :--- |
| `/bal [player]` | Check balance. | `aureleconomy.bal` |
| `/pay <player> <amount>`| Pay another player. | `aureleconomy.pay` |
| `/market [search]` | Open the market or search for an item. | `aureleconomy.market` |
| `/stocks` | View live market price trends. | `aureleconomy.stocks` |
| `/orders` | Open the Buy Orders menu. | `aureleconomy.orders` |
| `/ah` | Open the Auction House. | `aureleconomy.ah` |
| `/sell` | Open the bulk selling interface. | `aureleconomy.sell` |

### Admin Commands
| Command | Description | Permission |
| :--- | :--- | :--- |
| `/eco <give/take/set> <player> <amount>` | Admin balance control. | `aureleconomy.admin` |
| `/aurel reload` | Reload plugin configuration. | `aureleconomy.admin` |

## ⚙️ Installation
1.  Download `Aurelium-1.2.1.jar`.
2.  Place it in your server's `plugins/` folder.
3.  **Restart** the server. (Vault will auto-install if missing).

## 🔧 Configuration (`config.yml`)
Server owners have full control over the economy:
```yaml
market:
  enabled: true
  dynamic-pricing: true
  price-floor: 0.2          # 20% of base price
  price-ceiling: 5.0        # 500% of base price
  price-recovery:
    enabled: true
    rate: 0.01              # 1% recovery per interval
    interval-minutes: 10

auction-house:
  enabled: true
  max-listings-per-player: 10
  listing-fee-percent: 2.0

buy-orders:
  enabled: true
  max-orders-per-player: 5
  creation-fee: 50.0
```

## 🌍 Localization
A detailed `messages.yml` is generated, supporting **MiniMessage** formatting (`<gradient>`, `<green>`, etc.) for every player-facing interaction.

## ❓ Troubleshooting
-   **Requirement**: Ensure you are running **Paper 1.21.11** or higher and using **Java 21**.
-   **Log Cleanup**: Aurelium uses Paper's structured logging; check your console for clean, color-coded error reports.

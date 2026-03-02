# Aurelium Patch Notes

## v1.2.1 (Minecraft 1.21.11 Update)

### 🚀 Core Updates
- **Minecraft 1.21.11 Support**: Updated the `paper-api` dependency and `paperweight` versions to compile natively against Paper 1.21.11. 
- **API Parity Fix**: Replaced the removed `Material.CHAIN` constant with a dynamic lookup and updated its market fallback material from `IRON_BLOCK` to a more cost-effective `IRON_NUGGET`.

### 🛒 Market Updates
- **Search Bug Fixed**: The `/market search` function previously iterated through the internal `Category.ALL_ITEMS` list (a backend survival-items master list for the global order system), causing hundreds of un-buyable items to falsely appear in market search results. This category is now explicitly excluded from market searches. 
- **Pale Garden Update Items**: Added all newly introduced Pale Oak crafts to the Market (`Category.WOOD`), including logs, stripped variants, plaques, doors, trapdoors, signs, boats, and pale moss blocks/carpets.
- **Copper Update Items**: Added new Copper variants to the Market (`Category.COPPER`), including Chests, Lanterns, Torches, Chains, and Bars (including all waxed/oxidized stages).
- **Extra Nature Items**: Added Firefly Bushes, Leaf Litter, Cactus Flowers, Wildflowers, and Short/Tall Dry Grass to `Category.NATURE`.
- **GUI Price Display Fixes**: Items possessing no intrinsic market base price (like Smelted Ores) previously defaulted to an inaccurate `1.0` Aurel display value within the `/stocks` menu and `/orders create` GUI. They now intelligently retrieve and display their exact `Last Sold Price` directly from recent player trading history. Additionally, all 120+ Enchanted Books were incorrectly bundled under a single query; they now properly isolate their prices based on their specific enchantment and level.
- **Stocks GUI Sorting**: The `/stocks` menu has been heavily upgraded to feature dynamic live-sorting. Items are now beautifully organized: true dynamic-market items are prioritized at the top of the menu, followed sequentially by pure-trading (Order/Auction) items. Both sections are then sub-sorted by **Absolute Percentage Change**, ensuring the most volatile and active items (crashing or skyrocketing) are always displayed on the very first page!
- *Note:* To prevent compilation or runtime errors on servers running without experimental Winter Drop flags, all new 1.21.11 items are integrated via dynamic string matching rather than strict enum constants.

### 🪲 Bug Fixes
- Fixed a visual bug in `/market` where the "Previous Page" paper icon mysteriously stopped rendering after navigating to the second page.
- Fixed a severe bug where purchasing multiple items (e.g. 64x) simultaneously evaluated dynamic market volatility as a simple 1x transaction. Large bulk purchases now apply mathematically sound compound inflation models.
- Fixed a bug where recently added Server items (like Pale/Copper) were locked at `1.0` base prices because older server configuration data was mistakenly permanently overriding their new values.

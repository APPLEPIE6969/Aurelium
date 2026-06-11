    public void addCustomMarketItem(String canonicalId, CustomMarketItem customItem) {
        String key = canonicalId;

        // Resolve effective prices: treat -1 (unset sentinel) as "use vanilla material price"
        BigDecimal effectiveBuyPrice = customItem.getBuyPrice();
        if (effectiveBuyPrice.compareTo(BigDecimal.ZERO) < 0) {
            effectiveBuyPrice = getBuyPrice(customItem.getItemStack().getType());
            if (effectiveBuyPrice.compareTo(BigDecimal.ZERO) <= 0) {
                // Last resort: set a small positive default so base price is never negative
                effectiveBuyPrice = BigDecimal.ONE;
            }
        }

        BigDecimal effectiveSellPrice = customItem.getSellPrice();
        if (effectiveSellPrice.compareTo(BigDecimal.ZERO) < 0) {
            effectiveSellPrice = effectiveBuyPrice.multiply(defaultSellRatio);
        }

        if (!entryCache.containsKey(key)) {
            MarketEntry entry = new MarketEntry(customItem.getItemStack().getType(),
                effectiveBuyPrice.doubleValue(), customItem.getDisplayName());
            entryCache.put(key, entry);
        }

        if (!buyPrices.containsKey(key) || effectiveBuyPrice.compareTo(BigDecimal.ZERO) > 0) {
            buyPrices.put(key, effectiveBuyPrice);
        }
        if (!sellPrices.containsKey(key) || effectiveSellPrice.compareTo(BigDecimal.ZERO) > 0) {
            sellPrices.put(key, effectiveSellPrice);
        }

        if (!itemCurrencies.containsKey(key)) {
            itemCurrencies.put(key, plugin.getEconomyManager().getDefaultCurrency());
        }
    }

}
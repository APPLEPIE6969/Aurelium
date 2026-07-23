package com.aureleconomy.web;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.scanner.CustomItemRegistry;
import com.aureleconomy.scanner.CustomMarketItem;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class ApiHandler implements HttpHandler {

 private final AurelEconomy plugin;
 private final WebSessionManager sessions;

 public ApiHandler(AurelEconomy plugin, WebSessionManager sessions) {
 this.plugin = plugin;
 this.sessions = sessions;
 }

 @Override
 public void handle(HttpExchange exchange) throws IOException {
 // CORS Security: Whitelist-based validation
 String origin = exchange.getRequestHeaders().getFirst("Origin");
 List<String> allowedOrigins = plugin.getConfig().getStringList("web.local.cors-allowed-origins");

 if (origin != null && !allowedOrigins.isEmpty()) {
 if (allowedOrigins.contains(origin)) {
 exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
 exchange.getResponseHeaders().set("Vary", "Origin");
 }
 }

 exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
 exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
 exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");

 if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
 exchange.sendResponseHeaders(204, -1);
 return;
 }

 String path = exchange.getRequestURI().getPath();
 Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());

 // Validate session
 UUID playerUuid = sessions.validate(params.get("token"));
 if (playerUuid == null) {
 sendJson(exchange, 401, "{\"error\":\"Invalid or expired session. Use /web in-game.\"}");
 return;
 }

 try {
 switch (path) {
 case "/api/player" -> handlePlayer(exchange, playerUuid);
 case "/api/categories" -> handleCategories(exchange);
 case "/api/items" -> handleItems(exchange, params);
 case "/api/search" -> handleSearch(exchange, params);
 case "/api/buy" -> handleBuy(exchange, playerUuid, params);
 case "/api/auctions" -> handleAuctions(exchange, params);
 case "/api/auctions/buy" -> handleAuctionBuy(exchange, playerUuid, params);
 default -> sendJson(exchange, 404, "{\"error\":\"Not found\"}");
 }
 } catch (Exception e) {
 plugin.getComponentLogger().error("Web API error", e);
 sendJson(exchange, 500, "{\"error\":\"Internal server error\"}");
 }
 }

 // ── GET /api/player ──────────────────────────────────────────────

 private void handlePlayer(HttpExchange exchange, UUID uuid) throws IOException {
 OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
 String name = player.getName() != null ? player.getName() : uuid.toString();

 String defaultCurrency = plugin.getEconomyManager().getDefaultCurrency();
 Map<String, Object> currencies = new LinkedHashMap<>();

 BigDecimal defaultBal = plugin.getEconomyManager().getBalance(player, defaultCurrency);
 currencies.put(defaultCurrency, defaultBal);

 if (plugin.getConfig().isConfigurationSection("economy.currencies")) {
 for (String currencyName : plugin.getConfig().getConfigurationSection("economy.currencies")
 .getKeys(false)) {
 if (!currencyName.equals(defaultCurrency)) {
 BigDecimal bal = plugin.getEconomyManager().getBalance(player, currencyName);
 currencies.put(currencyName, bal);
 }
 }
 }

 StringBuilder json = new StringBuilder();
 json.append("{\"name\":").append(jsonStr(name));
 json.append(",\"uuid\":").append(jsonStr(uuid.toString()));
 json.append(",\"defaultCurrency\":").append(jsonStr(defaultCurrency));
 json.append(",\"balances\":{");
 int i = 0;
 for (var entry : currencies.entrySet()) {
 if (i++ > 0)
 json.append(",");
 json.append(jsonStr(entry.getKey())).append(":").append(entry.getValue());
 }
 json.append("}}");

 sendJson(exchange, 200, json.toString());
 }

 // ── GET /api/categories ──────────────────────────────────────────

 private void handleCategories(HttpExchange exchange) throws IOException {
 StringBuilder json = new StringBuilder("[");
 Category[] cats = Category.values();
 for (int i = 0; i < cats.length; i++) {
 Category cat = cats[i];
 if (i > 0)
 json.append(",");
 json.append("{\"id\":").append(jsonStr(cat.name()));
 json.append(",\"name\":").append(jsonStr(cat.name));
 json.append(",\"icon\":").append(jsonStr(cat.icon.name().toLowerCase()));
 json.append(",\"itemCount\":").append(MarketItems.getItems(cat).size());
 json.append("}");
 }
 json.append("]");
 sendJson(exchange, 200, json.toString());
 }

 // ── GET /api/items?category=X&page=0 ─────────────────────────────

 private void handleItems(HttpExchange exchange, Map<String, String> params) throws IOException {
 String catName = params.getOrDefault("category", "");
 int page = parseIntParam(params, "page", 0);
 int perPage = 28;

 Category cat;
 try {
 cat = Category.valueOf(catName);
 } catch (IllegalArgumentException e) {
 sendJson(exchange, 400, "{\"error\":\"Invalid category\"}");
 return;
 }

 List<MarketEntry> all = MarketItems.getItems(cat).stream()
 .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
 .toList();

 int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / perPage));
 int start = page * perPage;
 int end = Math.min(start + perPage, all.size());

 List<MarketEntry> pageItems = (start < all.size()) ? all.subList(start, end) : List.of();

 sendJson(exchange, 200, buildItemsJson(pageItems, page, totalPages, all.size()));
 }

 // ── GET /api/search?q=X&page=0 ───────────────────────────────────

 private void handleSearch(HttpExchange exchange, Map<String, String> params) throws IOException {
 String query = params.getOrDefault("q", "").toLowerCase();
 int page = parseIntParam(params, "page", 0);
 int perPage = 28;

 if (query.isEmpty()) {
 sendJson(exchange, 400, "{\"error\":\"Missing search query\"}");
 return;
 }

 List<MarketEntry> results = new ArrayList<>();
 for (Category cat : Category.values()) {
 if (cat == Category.ALL_ITEMS)
 continue;
 for (MarketEntry entry : MarketItems.getItems(cat)) {
 if (plugin.getMarketManager().isBlacklisted(entry.material))
 continue;
 String name = (entry.customName != null ? entry.customName : entry.material.name()).toLowerCase();
 if (name.contains(query)) {
 results.add(entry);
 }
 }
 }

 int totalPages = Math.max(1, (int) Math.ceil((double) results.size() / perPage));
 int start = page * perPage;
 int end = Math.min(start + perPage, results.size());
 List<MarketEntry> pageItems = (start < results.size()) ? results.subList(start, end) : List.of();

 sendJson(exchange, 200, buildItemsJson(pageItems, page, totalPages, results.size()));
 }

 // ── POST /api/buy?item=DIAMOND&amount=1 ──────────────────────────

 private void handleBuy(HttpExchange exchange, UUID playerUuid, Map<String, String> params) throws IOException {
 if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
 sendJson(exchange, 405, "{\"error\":\"Use POST\"}");
 return;
 }

 String itemKey = params.getOrDefault("item", "");
 // Security: validate itemKey format to prevent injection
 if (!isValidItemKey(itemKey)) {
 sendJson(exchange, 400, "{\"error\":\"Invalid item format. Expected lowercase namespace:key or material name.\"}");
 return;
 }
 int amount = parseIntParam(params, "amount", 1);
 if (amount < 1 || amount > 64)
 amount = 1;

 // Resolve the item
 BigDecimal buyPrice;
 String currency;
 Material material;

 try {
 material = Material.valueOf(itemKey.toUpperCase());
 buyPrice = plugin.getMarketManager().getBuyPrice(material);
 currency = plugin.getMarketManager().getCurrency(material);
 } catch (IllegalArgumentException e) {
 buyPrice = plugin.getMarketManager().getBuyPrice(itemKey);
 currency = plugin.getMarketManager().getCurrency(itemKey);
 material = Material.SPAWNER;
 }

 if (buyPrice.compareTo(BigDecimal.ZERO) <= 0) {
 sendJson(exchange, 400, "{\"error\":\"Item not for sale\"}");
 return;
 }

 BigDecimal totalCost = buyPrice.multiply(BigDecimal.valueOf(amount));
 OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);

 if (!plugin.getEconomyManager().has(offlinePlayer, totalCost, currency)) {
 String formatted = plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency);
 sendJson(exchange, 400, "{\"error\":\"Not enough funds. You need " + escapeJson(formatted) + "\"}");
 return;
 }

 final BigDecimal finalCost = totalCost;
 final int finalAmount = amount;
 final String finalCurrency = currency;
 final Material finalMaterial = material;
 final String finalItemKey = itemKey;

 CompletableFuture<String> future = new CompletableFuture<>();

 Bukkit.getScheduler().runTask(plugin, () -> {
 Player onlinePlayer = Bukkit.getPlayer(playerUuid);
 if (onlinePlayer == null || !onlinePlayer.isOnline()) {
 future.complete("{\"error\":\"You must be online to buy items\"}");
 return;
 }

 ItemStack toGive = new ItemStack(finalMaterial, finalAmount);
 if (!com.aureleconomy.utils.InventoryUtils.hasSpace(onlinePlayer.getInventory(), toGive, finalAmount)) {
 future.complete("{\"error\":\"Your inventory is full\"}");
 return;
 }

 plugin.getEconomyManager().withdraw(onlinePlayer, finalCost, finalCurrency);
 onlinePlayer.getInventory().addItem(toGive);
 plugin.getMarketManager().onTransaction(finalItemKey, true, finalAmount);

 BigDecimal newBalance = plugin.getEconomyManager().getBalance(onlinePlayer, finalCurrency);
 String formatted = plugin.getEconomyManager().getFormattedWithSymbol(finalCost, finalCurrency);
 String balFormatted = plugin.getEconomyManager().getFormattedWithSymbol(newBalance, finalCurrency);

 onlinePlayer.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
 "<green><bold>✔</bold> Web purchase: <white>" + finalAmount + "x "
 + finalMaterial.name().replace("_", " ") + "</white> for <gold>" + formatted
 + "</gold></green>"));

 future.complete("{\"success\":true,\"spent\":" + jsonStr(formatted)
 + ",\"newBalance\":" + newBalance
 + ",\"newBalanceFormatted\":" + jsonStr(balFormatted) + "}");
 });

 try {
 String result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
 int code = result.contains("\"error\"") ? 400 : 200;
 sendJson(exchange, code, result);
 } catch (Exception e) {
 sendJson(exchange, 500, "{\"error\":\"Transaction timed out\"}");
 }
 }

 // ── GET /api/auctions?mode=all&page=0 ────────────────────────────

 private void handleAuctions(HttpExchange exchange, Map<String, String> params) throws IOException {
 String modeFilter = params.getOrDefault("mode", "all"); // all, bin, auction, stack, unit
 int page = parseIntParam(params, "page", 0);
 int perPage = 28;

 List<AuctionItem> all = plugin.getAuctionManager().getActiveAuctions().stream()
 .filter(ai -> !ai.isEnded())
 .toList();

 // Filter by purchase mode if requested
 if ("stack".equalsIgnoreCase(modeFilter)) {
 all = all.stream().filter(ai -> ai.getPurchaseMode() == AuctionItem.PurchaseMode.STACK).toList();
 } else if ("unit".equalsIgnoreCase(modeFilter)) {
 all = all.stream().filter(ai -> ai.getPurchaseMode() == AuctionItem.PurchaseMode.UNIT).toList();
 }

 int totalPages = Math.max(1, (int) Math.ceil((double) all.size() / perPage));
 int start = page * perPage;
 int end = Math.min(start + perPage, all.size());
 List<AuctionItem> pageItems = (start < all.size()) ? all.subList(start, end) : List.of();

 StringBuilder json = new StringBuilder();
 json.append("{\"page\":").append(page);
 json.append(",\"totalPages\":").append(totalPages);
 json.append(",\"totalItems\":").append(all.size());
 json.append(",\"items\":[");

 for (int j = 0; j < pageItems.size(); j++) {
 AuctionItem ai = pageItems.get(j);
 if (j > 0) json.append(",");

 String displayName = getItemDisplayName(ai.getItem());
 String materialName = ai.getItem().getType().name().toLowerCase();
 int quantity = ai.getAvailableQuantity();
 BigDecimal price = ai.getPricePerUnit();
 String currency = ai.getCurrency();
 String sellerName = Bukkit.getOfflinePlayer(ai.getSeller()).getName();

 json.append("{\"id\":").append(ai.getId());
 json.append(",\"seller\":").append(jsonStr(sellerName != null ? sellerName : "Unknown"));
 json.append(",\"item\":").append(jsonStr(displayName));
 json.append(",\"material\":").append(jsonStr(materialName));
 json.append(",\"quantity\":").append(quantity);
 json.append(",\"price\":").append(price);
 json.append(",\"priceFormatted\":").append(jsonStr(plugin.getEconomyManager().getFormattedWithSymbol(price, currency)));
 json.append(",\"currency\":").append(jsonStr(currency));
 json.append(",\"isBin\":").append(ai.isBin());
 json.append(",\"purchaseMode\":").append(jsonStr(ai.getPurchaseMode().name()));
 json.append(",\"expires\":").append(ai.getExpiration());
 json.append(",\"totalCost\":").append(price.multiply(BigDecimal.valueOf(quantity)));
 }

 json.append("]}");
 sendJson(exchange, 200, json.toString());
 }

 // ── POST /api/auctions/buy?id=X&quantity=N ───────────────────────

 private void handleAuctionBuy(HttpExchange exchange, UUID playerUuid, Map<String, String> params) throws IOException {
 if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
 sendJson(exchange, 405, "{\"error\":\"Use POST\"}");
 return;
 }

 int auctionId = parseIntParam(params, "id", -1);
 if (auctionId < 1) {
 sendJson(exchange, 400, "{\"error\":\"Invalid auction ID\"}");
 return;
 }
 int quantity = parseIntParam(params, "quantity", 1);

 AuctionItem ai = plugin.getAuctionManager().getAuctionById(auctionId);
 if (ai == null || ai.isEnded()) {
 sendJson(exchange, 404, "{\"error\":\"Auction not found or ended\"}");
 return;
 }

 Player buyer = Bukkit.getPlayer(playerUuid);
 if (buyer == null || !buyer.isOnline()) {
 sendJson(exchange, 400, "{\"error\":\"You must be online\"}");
 return;
 }

 if (ai.getPurchaseMode() == AuctionItem.PurchaseMode.STACK) {
 // STACK mode: must buy all
 quantity = ai.getAvailableQuantity();
 }

 BigDecimal totalCost = ai.getPricePerUnit().multiply(BigDecimal.valueOf(quantity))
 .setScale(2, BigDecimal.ROUND_HALF_UP);
 String currency = ai.getCurrency();

 if (!plugin.getEconomyManager().has(buyer, totalCost, currency)) {
 String formatted = plugin.getEconomyManager().getFormattedWithSymbol(totalCost, currency);
 sendJson(exchange, 400, "{\"error\":\"Not enough funds. You need " + escapeJson(formatted) + "\"}");
 return;
 }

 final int finalQuantity = quantity;
 final BigDecimal finalCost = totalCost;
 final String finalCurrency = currency;
 final AuctionItem finalAi = ai;

 CompletableFuture<String> future = new CompletableFuture<>();

 Bukkit.getScheduler().runTask(plugin, () -> {
 int purchased = plugin.getAuctionManager().purchaseUnits(finalAi, buyer, finalQuantity);
 if (purchased <= 0) {
 future.complete("{\"error\":\"Purchase failed\"}");
 return;
 }

 BigDecimal newBal = plugin.getEconomyManager().getBalance(buyer, finalCurrency);
 String spentFormatted = plugin.getEconomyManager().getFormattedWithSymbol(finalCost, finalCurrency);
 String balFormatted = plugin.getEconomyManager().getFormattedWithSymbol(newBal, finalCurrency);

 buyer.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
 "<green><bold>✔</bold> Purchased " + purchased + "x " + getItemDisplayName(finalAi.getItem())
 + " for <gold>" + spentFormatted + "</gold></green>"));

 future.complete("{\"success\":true,\"purchased\":" + purchased
 + ",\"spent\":" + jsonStr(spentFormatted)
 + ",\"newBalanceFormatted\":" + jsonStr(balFormatted) + "}");
 });

 try {
 String result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
 int code = result.contains("\"error\"") ? 400 : 200;
 sendJson(exchange, code, result);
 } catch (Exception e) {
 sendJson(exchange, 500, "{\"error\":\"Transaction timed out\"}");
 }
 }

 // ── Helpers ───────────────────────────────────────────────────────

 private String buildItemsJson(List<MarketEntry> items, int page, int totalPages, int totalItems) {
 StringBuilder json = new StringBuilder();
 json.append("{\"page\":").append(page);
 json.append(",\"totalPages\":").append(totalPages);
 json.append(",\"totalItems\":").append(totalItems);
 json.append(",\"items\":[");

 for (int i = 0; i < items.size(); i++) {
 MarketEntry entry = items.get(i);
 if (i > 0)
 json.append(",");

 String key = (entry.material == Material.SPAWNER && entry.customName != null)
 ? entry.customName
 : entry.material.name();
 String displayName = entry.customName != null ? entry.customName
 : entry.material.name().replace("_", " ");
 BigDecimal buyPrice = (entry.material == Material.SPAWNER && entry.customName != null)
 ? plugin.getMarketManager().getBuyPrice(entry.customName)
 : plugin.getMarketManager().getBuyPrice(entry.material);
 String currency = (entry.material == Material.SPAWNER && entry.customName != null)
 ? plugin.getMarketManager().getCurrency(entry.customName)
 : plugin.getMarketManager().getCurrency(entry.material);

 json.append("{\"key\":").append(jsonStr(key));
 json.append(",\"material\":").append(jsonStr(entry.material.name().toLowerCase()));
 json.append(",\"name\":").append(jsonStr(displayName));
 json.append(",\"price\":").append(buyPrice.toString());
 json.append(",\"priceFormatted\":").append(jsonStr(plugin.getEconomyManager().getFormattedWithSymbol(buyPrice, currency)));
 json.append(",\"currency\":").append(jsonStr(currency));
   json.append("}");
   }
 json.append("]}");
 return json.toString();
 }

 private Map<String, String> parseQuery(String query) {
 Map<String, String> params = new HashMap<>();
 if (query == null || query.isEmpty())
 return params;
 for (String pair : query.split("&")) {
 String[] kv = pair.split("=", 2);
 if (kv.length == 2) {
 params.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
 URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
 }
 }
 return params;
 }

 private int parseIntParam(Map<String, String> params, String key, int defaultVal) {
 try {
 return Integer.parseInt(params.getOrDefault(key, String.valueOf(defaultVal)));
 } catch (NumberFormatException e) {
 return defaultVal;
 }
 }

 private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
 byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
 exchange.sendResponseHeaders(code, bytes.length);
 try (OutputStream os = exchange.getResponseBody()) {
 os.write(bytes);
 }
 }

 private static String jsonStr(String s) {
 if (s == null)
 return "null";
 return "\"" + escapeJson(s) + "\"";
 }

 /**
 * Escape JSON string values. Covers all required control characters.
 */
 private static String escapeJson(String s) {
 return s.replace("\\", "\\\\")
 .replace("\"", "\\\"")
 .replace("\n", "\\n")
 .replace("\r", "\\r")
 .replace("\t", "\\t")
 .replace("\b", "\\b")
 .replace("\f", "\\f");
 }

 /**
 * Validate itemKey format: must be namespace:key or plain material name.
 * Prevents injection of special characters into SQL queries or item lookups.
 */
 private static boolean isValidItemKey(String itemKey) {
 if (itemKey == null || itemKey.isEmpty()) return false;
 return itemKey.matches("^[a-zA-Z0-9_.-]+(:[a-zA-Z0-9_./-]+)?$");
 }

 private String getItemDisplayName(ItemStack item) {
 if (item.hasItemMeta()) {
 ItemMeta meta = item.getItemMeta();
 if (meta.hasDisplayName()) {
 return meta.displayName() != null ? net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
 .serialize(meta.displayName()) : item.getType().name();
 }
 }
 return item.getType().name().replace("_", " ");
 }
}

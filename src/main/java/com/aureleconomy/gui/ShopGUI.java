package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.market.MarketItems;
import com.aureleconomy.market.MarketItems.Category;
import com.aureleconomy.market.MarketItems.MarketEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Modern Market GUI using MiniMessage gradients, glass-pane backgrounds,
 * and clean OOP InventoryHolder architecture.
 *
 * Pages:
 * null category → Category selector (3 rows, 27 slots)
 * valid category → Item listing (6 rows, 54 slots, paginated)
 */
public class ShopGUI extends GUIHolder {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Map<java.util.UUID, Long> COOLDOWNS = new HashMap<>();
    private static final long COOLDOWN_MS = 150;

    private final AurelEconomy plugin;
    private final Category category;
    private final int page;

    // Slot → data mappings
    private final Map<Integer, Category> categorySlots = new HashMap<>();
    private final Map<Integer, MarketEntry> itemSlots = new HashMap<>();

    private String searchQuery = null;

    // ─── Glass pane filler ────────────────────────────────────────────
    private static final ItemStack FILLER;
    static {
        FILLER = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = FILLER.getItemMeta();
        m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        FILLER.setItemMeta(m);
    }

    private static final ItemStack ACCENT_FILLER;
    static {
        ACCENT_FILLER = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta m = ACCENT_FILLER.getItemMeta();
        m.displayName(Component.text(" ").decoration(TextDecoration.ITALIC, false));
        ACCENT_FILLER.setItemMeta(m);
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // Constructors
    // ╚═══════════════════════════════════════════════════════════════╝

    /** Open the category selector. */
    public ShopGUI(AurelEconomy plugin, Player player) {
        this(plugin, player, null, 0);
    }

    /** Open a specific category / page. */
    public ShopGUI(AurelEconomy plugin, Player player, Category category, int page) {
        this.plugin = plugin;
        this.category = category;
        this.page = page;

        if (category == null) {
            // Category selector — 3 rows
            this.inventory = plugin.getServer().createInventory(this, 27,
                    MM.deserialize("<gradient:gold:yellow><bold>Server Market</bold></gradient>"));
            setupCategories();
        } else {
            // Item listing — 6 rows
            this.inventory = plugin.getServer().createInventory(this, 54,
                    MM.deserialize("<gradient:#55AAFF:#55FFFF>" + category.name + "</gradient>"));
            setupItems();
        }
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // Category Selector (27 slots)
    // ╚═══════════════════════════════════════════════════════════════╝

    private void setupCategories() {
        // Fill everything with glass
        fillGlass(27);

        // Place categories in a centered row
        Category[] cats = Category.values();
        // Slots 10-16 = 7 slots for row-2 center, 19-25 = 7 more if needed
        int[] row2 = { 10, 11, 12, 13, 14, 15, 16 };
        int[] row3 = { 19, 20, 21, 22, 23, 24, 25 };

        int idx = 0;
        for (Category cat : cats) {
            int[] targetRow = idx < 7 ? row2 : row3;
            int slotIdx = idx < 7 ? idx : idx - 7;
            if (slotIdx >= targetRow.length)
                break;

            int slot = targetRow[slotIdx];

            ItemStack icon = new ItemStack(cat.icon);
            ItemMeta meta = icon.getItemMeta();

            meta.displayName(MM.deserialize("<gold><bold>" + cat.name + "</bold></gold>")
                    .decoration(TextDecoration.ITALIC, false));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());
            lore.add(MM.deserialize("<gray>Click to browse</gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MM.deserialize("<yellow>➤ View Items</yellow>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);

            icon.setItemMeta(meta);
            inventory.setItem(slot, icon);
            categorySlots.put(slot, cat);
            idx++;
        }

        // Search button — slot 4 (top center)
        ItemStack search = new ItemStack(Material.COMPASS);
        ItemMeta sMeta = search.getItemMeta();
        sMeta.displayName(MM.deserialize("<aqua><bold>🔍 Search Items</bold></aqua>")
                .decoration(TextDecoration.ITALIC, false));
        List<Component> sLore = new ArrayList<>();
        sLore.add(Component.empty());
        sLore.add(MM.deserialize("<gray>Click to search by name</gray>")
                .decoration(TextDecoration.ITALIC, false));
        sMeta.lore(sLore);
        search.setItemMeta(sMeta);
        inventory.setItem(4, search);
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // Item Listing (54 slots)
    // ╚═══════════════════════════════════════════════════════════════╝

    @SuppressWarnings("deprecation")
    private void setupItems() {
        List<MarketEntry> allItems;
        if (searchQuery != null) {
            allItems = searchItems(searchQuery);
        } else {
            allItems = MarketItems.getItems(category);
        }

        // Filter blacklisted
        allItems = allItems.stream()
                .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                .toList();

        int itemsPerPage = 28; // Slots inside the bordered area
        int totalItems = allItems.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / itemsPerPage));

        // Fill borders with glass
        fillBorderedLayout();

        // ── Navigation bar (bottom row 45-53) ──
        // Back button (slot 45)
        {
            ItemStack back = new ItemStack(Material.ARROW);
            ItemMeta m = back.getItemMeta();
            m.displayName(MM.deserialize(searchQuery != null
                    ? "<red><bold>✖ Clear Search</bold></red>"
                    : "<red><bold>← Back</bold></red>")
                    .decoration(TextDecoration.ITALIC, false));
            back.setItemMeta(m);
            inventory.setItem(45, back);
        }

        // Previous page (slot 48)
        if (page > 0) {
            ItemStack prev = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta m = prev.getItemMeta();
            m.displayName(MM.deserialize("<yellow><bold>◀ Previous Page</bold></yellow>")
                    .decoration(TextDecoration.ITALIC, false));
            prev.setItemMeta(m);
            inventory.setItem(48, prev);
        }

        // Page indicator (slot 49)
        {
            ItemStack info = new ItemStack(Material.BOOK);
            ItemMeta m = info.getItemMeta();
            m.displayName(MM
                    .deserialize("<white>Page <gold>" + (page + 1) + "</gold>/<gold>" + totalPages + "</gold></white>")
                    .decoration(TextDecoration.ITALIC, false));
            info.setItemMeta(m);
            inventory.setItem(49, info);
        }

        // Next page (slot 50)
        if (page < totalPages - 1) {
            ItemStack next = new ItemStack(Material.SPECTRAL_ARROW);
            ItemMeta m = next.getItemMeta();
            m.displayName(MM.deserialize("<yellow><bold>Next Page ▶</bold></yellow>")
                    .decoration(TextDecoration.ITALIC, false));
            next.setItemMeta(m);
            inventory.setItem(50, next);
        }

        // Search button (slot 53)
        {
            ItemStack compass = new ItemStack(Material.COMPASS);
            ItemMeta m = compass.getItemMeta();
            m.displayName(MM.deserialize(searchQuery != null
                    ? "<aqua><bold>🔍 " + searchQuery + "</bold></aqua>"
                    : "<aqua><bold>🔍 Search</bold></aqua>")
                    .decoration(TextDecoration.ITALIC, false));
            compass.setItemMeta(m);
            inventory.setItem(53, compass);
        }

        // ── Content area (slots 10-16, 19-25, 28-34, 37-43) = 28 slots ──
        int[] contentSlots = getContentSlots();

        int startIndex = page * itemsPerPage;
        int endIndex = Math.min(startIndex + itemsPerPage, totalItems);

        for (int i = startIndex; i < endIndex; i++) {
            MarketEntry entry = allItems.get(i);
            int slotIdx = i - startIndex;
            if (slotIdx >= contentSlots.length)
                break;
            int slot = contentSlots[slotIdx];

            itemSlots.put(slot, entry);

            // ── Price & Currency ──
            double buyPrice;
            String currency;
            if (entry.material == Material.SPAWNER && entry.customName != null) {
                buyPrice = plugin.getMarketManager().getBuyPrice(entry.customName);
                currency = plugin.getMarketManager().getCurrency(entry.customName);
            } else {
                buyPrice = plugin.getMarketManager().getBuyPrice(entry.material);
                currency = plugin.getMarketManager().getCurrency(entry.material);
            }

            // ── Build the display item ──
            ItemStack display = buildDisplayItem(entry);
            ItemMeta meta = display.getItemMeta();

            // ── Item name ──
            Component displayName = entry.customName != null
                    ? MM.deserialize("<aqua><bold>" + entry.customName + "</bold></aqua>")
                    : Component.translatable(entry.material.translationKey())
                            .color(NamedTextColor.AQUA)
                            .decoration(TextDecoration.BOLD, true);
            meta.displayName(displayName.decoration(TextDecoration.ITALIC, false));

            // ── Lore ──
            List<Component> lore = new ArrayList<>();
            lore.add(Component.empty());

            if (buyPrice > 0) {
                lore.add(MM
                        .deserialize("<gray>Price: <green>" + plugin.getEconomyManager().format(buyPrice, currency)
                                + "</green></gray>")
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.empty());
                lore.add(MM.deserialize("<yellow>▸ Left-Click</yellow> <gray>to buy</gray> <white>×1</white>")
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(MM.deserialize("<yellow>▸ Shift-Click</yellow> <gray>to buy</gray> <white>×64</white>")
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(MM.deserialize("<red>Not for sale</red>")
                        .decoration(TextDecoration.ITALIC, false));
            }

            meta.lore(lore);
            // CustomModelData for resource packs
            meta.setCustomModelData(1001);
            display.setItemMeta(meta);

            inventory.setItem(slot, display);
        }
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // Click Handler
    // ╚═══════════════════════════════════════════════════════════════╝

    @Override
    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true); // Always cancel — no item theft

        Player clicker = (Player) event.getWhoClicked();

        // Prevent clicking in bottom inventory
        if (event.getClickedInventory() != null && event.getClickedInventory().equals(clicker.getInventory())) {
            return;
        }

        int slot = event.getSlot();

        // ── Category selector page ──
        if (category == null) {
            if (categorySlots.containsKey(slot)) {
                clicker.playSound(clicker.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
                new ShopGUI(plugin, clicker, categorySlots.get(slot), 0).open(clicker);
            } else if (slot == 4) {
                promptSearch(clicker);
            }
            return;
        }

        // ── Item listing page ──

        // Cooldown
        long now = System.currentTimeMillis();
        java.util.UUID uid = clicker.getUniqueId();
        if (COOLDOWNS.containsKey(uid) && (now - COOLDOWNS.get(uid)) < COOLDOWN_MS) {
            return;
        }
        COOLDOWNS.put(uid, now);

        // Back
        if (slot == 45) {
            clicker.playSound(clicker.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 0.8f);
            if (searchQuery != null) {
                searchQuery = null;
                refreshItems();
            } else {
                new ShopGUI(plugin, clicker).open(clicker);
            }
            return;
        }

        // Previous page
        if (slot == 48 && page > 0) {
            clicker.playSound(clicker.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            ShopGUI prev = new ShopGUI(plugin, clicker, category, page - 1);
            prev.searchQuery = this.searchQuery;
            prev.open(clicker);
            return;
        }

        // Next page
        if (slot == 50) {
            // Calculate total pages for limit check
            List<MarketEntry> allItems;
            if (searchQuery != null) {
                allItems = searchItems(searchQuery);
            } else {
                allItems = MarketItems.getItems(category);
            }
            allItems = allItems.stream()
                    .filter(e -> !plugin.getMarketManager().isBlacklisted(e.material))
                    .toList();

            int itemsPerPage = 28;
            int totalItems = allItems.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / itemsPerPage));

            if (page < totalPages - 1) {
                clicker.playSound(clicker.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
                ShopGUI next = new ShopGUI(plugin, clicker, category, page + 1);
                next.searchQuery = this.searchQuery;
                next.open(clicker);
            }
            return;
        }

        // Search
        if (slot == 53) {
            promptSearch(clicker);
            return;
        }

        // ── Buy item ──
        if (itemSlots.containsKey(slot)) {
            handleBuy(clicker, itemSlots.get(slot), event.isShiftClick());
        }
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // Transaction
    // ╚═══════════════════════════════════════════════════════════════╝

    private void handleBuy(Player buyer, MarketEntry entry, boolean bulk) {
        int amount = bulk ? 64 : 1;

        // Resolve price + currency
        double unitPrice;
        String currency;
        if (entry.material == Material.SPAWNER && entry.customName != null) {
            unitPrice = plugin.getMarketManager().getBuyPrice(entry.customName);
            currency = plugin.getMarketManager().getCurrency(entry.customName);
        } else {
            unitPrice = plugin.getMarketManager().getBuyPrice(entry.material);
            currency = plugin.getMarketManager().getCurrency(entry.material);
        }

        if (unitPrice <= 0) {
            buyer.sendMessage(MM.deserialize("<red>This item is not for sale.</red>"));
            buyer.playSound(buyer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        double totalPrice = unitPrice * amount;

        // Funds check
        if (!plugin.getEconomyManager().has(buyer, totalPrice, currency)) {
            buyer.sendMessage(MM.deserialize("<red><bold>✖</bold> Not enough funds!</red> <gray>You need "
                    + plugin.getEconomyManager().format(totalPrice, currency) + "</gray>"));
            buyer.playSound(buyer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Build transaction item
        ItemStack given = buildTransactionItem(entry);
        given.setAmount(amount);

        // Inventory space check
        if (!com.aureleconomy.utils.InventoryUtils.hasSpace(buyer.getInventory(), given, amount)) {
            buyer.sendMessage(MM.deserialize("<red><bold>✖</bold> Your inventory is full!</red>"));
            buyer.playSound(buyer.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }

        // Execute
        plugin.getEconomyManager().withdraw(buyer, totalPrice, currency);
        buyer.getInventory().addItem(given);

        // Dynamic pricing
        String key = (entry.material == Material.SPAWNER && entry.customName != null)
                ? entry.customName
                : entry.material.name();
        plugin.getMarketManager().onTransaction(key, true, amount);

        // Feedback
        String itemName = entry.customName != null ? entry.customName : entry.material.name().replace("_", " ");
        buyer.sendMessage(MM.deserialize("<green><bold>✔</bold> Purchased <white>" + amount + "x " + itemName
                + "</white> for <gold>" + plugin.getEconomyManager().format(totalPrice, currency) + "</gold></green>"));
        buyer.playSound(buyer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // Helpers
    // ╚═══════════════════════════════════════════════════════════════╝

    /** Build a display item (spawner or standard). */
    private ItemStack buildDisplayItem(MarketEntry entry) {
        if (entry.material == Material.SPAWNER && entry.customName != null) {
            ItemStack spawner = new ItemStack(Material.SPAWNER);
            BlockStateMeta meta = (BlockStateMeta) spawner.getItemMeta();
            CreatureSpawner cs = (CreatureSpawner) meta.getBlockState();
            try {
                String mobName = entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                cs.setSpawnedType(EntityType.valueOf(mobName));
                meta.setBlockState(cs);
            } catch (IllegalArgumentException ignored) {
            }
            spawner.setItemMeta(meta);
            return spawner;
        }
        return new ItemStack(entry.material);
    }

    /** Build a clean transaction item (no lore). */
    private ItemStack buildTransactionItem(MarketEntry entry) {
        if (entry.material == Material.SPAWNER && entry.customName != null) {
            ItemStack spawner = new ItemStack(Material.SPAWNER);
            BlockStateMeta meta = (BlockStateMeta) spawner.getItemMeta();
            CreatureSpawner cs = (CreatureSpawner) meta.getBlockState();
            try {
                String mobName = entry.customName.replace(" Spawner", "").toUpperCase().replace(" ", "_");
                cs.setSpawnedType(EntityType.valueOf(mobName));
                meta.setBlockState(cs);
            } catch (IllegalArgumentException ignored) {
            }
            meta.displayName(Component.text(entry.customName, NamedTextColor.AQUA));
            spawner.setItemMeta(meta);
            return spawner;
        } else if (entry.material == Material.ENCHANTED_BOOK && entry.customName != null) {
            return com.aureleconomy.market.MarketItems.createEnchantedBook(entry.customName);
        }
        return new ItemStack(entry.material);
    }

    /** Fill all slots with filler glass. */
    private void fillGlass(int size) {
        for (int i = 0; i < size; i++) {
            inventory.setItem(i, FILLER.clone());
        }
    }

    /**
     * Fill a 54-slot chest with a bordered layout (glass border, empty content
     * area).
     */
    private void fillBorderedLayout() {
        // Fill everything with accent filler first
        for (int i = 0; i < 54; i++) {
            inventory.setItem(i, ACCENT_FILLER.clone());
        }

        // Top accent row (0-8): keep as accent
        // Content area: clear for items
        for (int slot : getContentSlots()) {
            inventory.setItem(slot, null);
        }

        // Bottom nav row stays as accent (will be overwritten by nav items)
    }

    /** Returns the 28 content slots in the bordered 54-layout. */
    private int[] getContentSlots() {
        return new int[] {
                10, 11, 12, 13, 14, 15, 16,
                19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34,
                37, 38, 39, 40, 41, 42, 43
        };
    }

    /** Search across all categories. */
    private List<MarketEntry> searchItems(String query) {
        List<MarketEntry> results = new ArrayList<>();
        String q = query.toLowerCase();
        for (Category cat : Category.values()) {
            if (cat == Category.ALL_ITEMS)
                continue;
            for (MarketEntry entry : MarketItems.getItems(cat)) {
                String name = (entry.customName != null ? entry.customName : entry.material.name()).toLowerCase();
                if (name.contains(q)) {
                    results.add(entry);
                }
            }
        }
        return results;
    }

    /** Prompt the player for a search query via chat. */
    private void promptSearch(Player target) {
        target.closeInventory();
        target.sendMessage(MM.deserialize(
                "<aqua><bold>🔍</bold></aqua> <gray>Type your search query in chat</gray> <dark_gray>(or</dark_gray> <red>cancel</red><dark_gray>)</dark_gray>"));
        plugin.getChatPromptManager().prompt(target, (input) -> {
            if (input.equalsIgnoreCase("cancel")) {
                new ShopGUI(plugin, target, category, page).open(target);
                return;
            }
            ShopGUI gui = new ShopGUI(plugin, target, category != null ? category : Category.ALL_ITEMS, 0);
            gui.searchQuery = input;
            gui.refreshItems();
            gui.open(target);
        });
    }

    /** Refresh the items view in-place. */
    private void refreshItems() {
        itemSlots.clear();
        inventory.clear();
        setupItems();
    }

    public void setSearchQuery(String query) {
        this.searchQuery = query;
        refreshItems();
    }
}

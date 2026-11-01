package com.aureleconomy.gui;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.auction.AuctionItem;
import com.aureleconomy.auction.AuctionManager;
import com.aureleconomy.utils.InventoryUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The collection bin hands back the item that was listed, not the picture of it
 * (M4).
 *
 * <p>The bin built a display stack, replaced its lore with "Click to Collect" and
 * tagged it, then delivered a clone of that display. A scanner item's own lore and
 * name were gone by the time it reached the player, which is a corrupted item: the
 * custom item registry identifies stacks by that lore. The canonical stack was in
 * the database the whole time, so the fix keeps it beside the display and delivers
 * it untouched.
 */
class AuctionCollectionTest {

    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<InventoryUtils> inventoryUtils;
    private MockedConstruction<ItemStack> stacks;
    private final PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
    private AurelEconomy plugin;
    private Player player;
    private PlayerInventory playerInventory;
    private AuctionManager auctions;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        stacks = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(mock(ItemMeta.class));
            when(stack.getAmount()).thenReturn(1);
        });

        inventory = mock(Inventory.class);
        when(inventory.firstEmpty()).thenReturn(0);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.createInventory(any(InventoryHolder.class), anyInt(), any(Component.class)))
                .thenReturn(inventory);

        inventoryUtils = mockStatic(InventoryUtils.class);
        inventoryUtils.when(() -> InventoryUtils.hasSpace(any(), any(), anyInt())).thenReturn(true);

        auctions = mock(AuctionManager.class);
        when(auctions.markCollectedAtomic(anyInt())).thenReturn(true);

        plugin = mock(AurelEconomy.class);
        when(plugin.namespace()).thenReturn("aurelium");
        when(plugin.getName()).thenReturn("Aurelium");
        when(plugin.getAuctionManager()).thenReturn(auctions);

        playerInventory = mock(PlayerInventory.class);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(playerInventory);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        inventoryUtils.close();
        stacks.close();
    }

    @Test
    @DisplayName("collecting delivers the stored item, not the display copy")
    void collectionDeliversTheCanonicalItem() {
        Component customLore = Component.text("Forged in the deep", NamedTextColor.LIGHT_PURPLE);

        ItemMeta storedMeta = mock(ItemMeta.class);
        when(storedMeta.lore()).thenReturn(new ArrayList<>(List.of(customLore)));
        ItemMeta displayMeta = mock(ItemMeta.class);
        when(displayMeta.lore()).thenReturn(new ArrayList<>(List.of(customLore)));
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(displayMeta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.INTEGER))).thenReturn(7);

        ItemStack listed = stack(Material.DIAMOND, storedMeta);
        ItemStack storedCanonical = stack(Material.DIAMOND, storedMeta);
        ItemStack delivered = stack(Material.DIAMOND, storedMeta);
        ItemStack display = stack(Material.DIAMOND, displayMeta);
        ItemStack displayClone = stack(Material.DIAMOND, displayMeta);

        when(listed.clone()).thenReturn(storedCanonical, display);
        when(storedCanonical.clone()).thenReturn(delivered);
        when(display.clone()).thenReturn(displayClone);

        // Run the edit, so the display really loses its own lore the way the old
        // code rewrote it before delivery.
        doAnswer(call -> {
            Consumer<ItemMeta> edit = call.getArgument(0);
            edit.accept(displayMeta);
            return null;
        }).when(display).editMeta(any());

        AuctionItem binEntry = new AuctionItem.Builder()
                .id(7)
                .seller(UUID.randomUUID())
                .item(listed)
                .price(BigDecimal.TEN)
                .build();
        when(auctions.getCollectionBin(any())).thenReturn(List.of(binEntry));

        AuctionGUI gui = new AuctionGUI(plugin, player, true);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getSlot()).thenReturn(0);
        when(event.getCurrentItem()).thenReturn(display);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.isShiftClick()).thenReturn(false);
        gui.handleClick(event);

        ArgumentCaptor<ItemStack> given = ArgumentCaptor.forClass(ItemStack.class);
        verify(playerInventory).addItem(given.capture());
        assertSame(delivered, given.getValue(),
                "the bin must deliver the stored stack, so the item arrives as it was listed");

        ArgumentCaptor<List<Component>> lore = ArgumentCaptor.forClass(List.class);
        verify(displayMeta, org.mockito.Mockito.atLeastOnce()).lore(lore.capture());
        List<Component> shown = lore.getAllValues().get(0);
        assertTrue(shown.contains(customLore),
                "the bin view must keep the item's own lore instead of replacing it");
        assertTrue(shown.contains(Component.text("Click to Collect", NamedTextColor.GREEN)),
                "the collect hint is added to the view, not used instead of the lore");
    }

    @Test
    @DisplayName("a bin entry with no stored stack is refused, not replaced by the display")
    void missingCanonicalIsRefused() {
        ItemMeta displayMeta = mock(ItemMeta.class);
        when(displayMeta.lore()).thenReturn(new ArrayList<>());
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(displayMeta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), eq(PersistentDataType.INTEGER))).thenReturn(9);

        ItemStack listed = stack(Material.DIAMOND, displayMeta);
        ItemStack display = stack(Material.DIAMOND, displayMeta);
        ItemStack displayClone = stack(Material.DIAMOND, displayMeta);
        // Only the display exists: the canonical stack is missing, which is what a
        // stale id looks like.
        when(listed.clone()).thenReturn(display);
        when(display.clone()).thenReturn(displayClone);

        when(auctions.getCollectionBin(any())).thenReturn(List.of());

        AuctionGUI gui = new AuctionGUI(plugin, player, true);

        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getSlot()).thenReturn(0);
        when(event.getCurrentItem()).thenReturn(display);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.isShiftClick()).thenReturn(false);
        gui.handleClick(event);

        verify(playerInventory, org.mockito.Mockito.never()).addItem(any(ItemStack.class));
        verify(auctions, org.mockito.Mockito.never()).markCollectedAtomic(anyInt());

        ArgumentCaptor<Component> told = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.atLeastOnce()).sendMessage(told.capture());
        assertTrue(told.getAllValues().stream()
                        .anyMatch(c -> plainText.serialize(c).contains("no longer in your collection bin")),
                "the player must be told the item is gone instead of being handed the display copy");
    }

    private static ItemStack stack(Material material, ItemMeta meta) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        when(stack.getAmount()).thenReturn(1);
        when(stack.getItemMeta()).thenReturn(meta);
        return stack;
    }
}

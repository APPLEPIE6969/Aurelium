package mock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import java.util.List;

/**
 * Represents a mock custom item for testing.
 */
public class MockItem {

    private final String namespacedId;
    private final ItemStack itemStack;

    public MockItem(String namespacedId, Material material, String displayName, int customModelData) {
        this.namespacedId = namespacedId;
        this.itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(displayName));
        meta.setCustomModelData(customModelData);
        meta.lore(List.of(Component.text("Custom item from ItemsAdder")));
        itemStack.setItemMeta(meta);
    }

    public String getNamespacedID() {
        return namespacedId;
    }

    public ItemStack getItemStack() {
        return itemStack.clone();
    }
}

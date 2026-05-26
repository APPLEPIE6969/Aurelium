package mock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import java.util.List;

public class MockEIItem {

    private final String id;
    private final ItemStack itemStack;

    public MockEIItem(String id, Material material, String displayName, int customModelData) {
        this.id = id;
        this.itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        meta.displayName(Component.text(displayName));
        meta.setCustomModelData(customModelData);
        meta.lore(List.of(Component.text("Executable item")));
        itemStack.setItemMeta(meta);
    }

    public String getId() {
        return id;
    }

    public ItemStack buildItem() {
        return itemStack.clone();
    }

    public ItemStack buildItem(int amount) {
        ItemStack clone = itemStack.clone();
        clone.setAmount(amount);
        return clone;
    }
}

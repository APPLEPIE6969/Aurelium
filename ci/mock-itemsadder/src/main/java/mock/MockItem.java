package mock;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.List;

public class MockItem {
    private final String id;
    private final ItemStack item;

    public MockItem(String id, Material material) {
        this.id = id;
        this.item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add("§7Mock item: " + id);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
    }

    public String getId() {
        return id;
    }

    public ItemStack getItemStack() {
        return item;
    }
}
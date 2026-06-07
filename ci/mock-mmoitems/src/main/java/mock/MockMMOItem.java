package mock;

import net.indyuce.mmoitems.api.MMOItem;
import net.indyuce.mmoitems.api.Type;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class MockMMOItem {
    private final String id;
    private final MMOItem mmoItem;

    public MockMMOItem(String id, Type type) {
        this.id = id;
        this.mmoItem = new MMOItem(id, type);
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return mmoItem.getType();
    }

    public ItemStack getItemStack() {
        return new ItemStack(Material.DIAMOND);
    }
}
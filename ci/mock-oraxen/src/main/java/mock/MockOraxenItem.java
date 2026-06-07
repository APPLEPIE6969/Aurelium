package mock;

import io.th0rgal.oraxen.api.OraxenItem;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class MockOraxenItem {
    private final String id;
    private final OraxenItem oraxenItem;

    public MockOraxenItem(String id) {
        this.id = id;
        this.oraxenItem = new OraxenItem(id, Material.DIAMOND.name());
    }

    public String getId() {
        return id;
    }

    public ItemStack build() {
        return new ItemStack(Material.DIAMOND);
    }
}
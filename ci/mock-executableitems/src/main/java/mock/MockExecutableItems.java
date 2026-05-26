package mock;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class MockExecutableItems extends JavaPlugin {

    private static final Map<String, MockEIItem> items = new LinkedHashMap<>();
    private static MockExecutableItems instance;

    @Override
    public void onEnable() {
        instance = this;
        items.put("explosive_tnt", new MockEIItem("explosive_tnt",
                org.bukkit.Material.TNT, "Explosive TNT", 20001));
        items.put("magic_compass", new MockEIItem("magic_compass",
                org.bukkit.Material.COMPASS, "Magic Compass", 20002));

        getLogger().info("ExecutableItems (mock) loaded with " + items.size() + " custom items");
    }

    public static MockExecutableItems getInstance() {
        return instance;
    }

    public static Collection<MockEIItem> getAllItems() {
        return items.values();
    }

    public static Map<String, MockEIItem> getItemsMap() {
        return items;
    }
}

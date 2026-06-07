package mock;

import net.indyuce.mmoitems.api.MMOItem;
import net.indyuce.mmoitems.api.Type;
import java.util.HashMap;
import java.util.Map;

public class MockMMOItems {
    private static MockMMOItems instance;
    private final Map<String, MMOItem> items = new HashMap<>();

    public MockMMOItems() {
        instance = this;
    }

    public static MockMMOItems getInstance() {
        return instance;
    }

    public void registerItem(String id, Type type) {
        items.put(id.toLowerCase(), new MMOItem(id, type));
    }

    public MMOItem getItem(String id) {
        return items.get(id.toLowerCase());
    }

    public boolean hasItem(String key) {
        return items.containsKey(key.toLowerCase());
    }
}
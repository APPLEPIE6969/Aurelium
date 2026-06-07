package mock;

import java.util.HashMap;
import java.util.Map;

public class MockItemsAdder {
    private static MockItemsAdder instance;
    private final Map<String, MockItem> items = new HashMap<>();

    public MockItemsAdder() {
        instance = this;
    }

    public static MockItemsAdder getInstance() {
        return instance;
    }

    public void registerItem(String id, MockItem item) {
        items.put(id.toLowerCase(), item);
    }

    public MockItem getCustomItem(String id) {
        return items.get(id.toLowerCase());
    }

    public boolean hasCustomItem(String id) {
        return items.containsKey(id.toLowerCase());
    }
}
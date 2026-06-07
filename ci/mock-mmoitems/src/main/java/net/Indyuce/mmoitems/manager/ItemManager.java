package net.indyuce.mmoitems.manager;

import net.indyuce.mmoitems.api.MMOItem;
import net.indyuce.mmoitems.api.Type;

import java.util.*;
import java.util.stream.Collectors;

public class ItemManager {
    private final Map<String, MMOItem> items = new HashMap<>();
    private final Map<String, Type> types = new HashMap<>();

    public void register(MMOItem item) {
        items.put(item.getId().toLowerCase(), item);
        // Register the type if not already present
        types.putIfAbsent(item.getType().getId().toLowerCase(), item.getType());
    }

    public MMOItem getItem(String id) {
        return items.get(id.toLowerCase());
    }

    public boolean exists(String key) {
        return items.containsKey(key.toLowerCase());
    }

    // API methods expected by scanner
    public Collection<Type> getAll() {
        return new ArrayList<>(types.values());
    }

    @SuppressWarnings("unchecked")
    public Map<String, MMOItem> getAll(Type type) {
        return items.entrySet().stream()
                .filter(e -> e.getValue().getType().getId().equalsIgnoreCase(type.getId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public Set<String> getAllTypes() {
        return types.keySet();
    }
}
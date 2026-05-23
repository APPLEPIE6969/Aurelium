package dev.lone.itemsadder.api;

import mock.MockItem;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shim at the real ItemsAdder API path that Aurelium's scanner reflects into.
 * Scanner calls: Class.forName("dev.lone.itemsadder.api.CustomStack")
 *   -> getItems() -> returns Map<String, CustomStack>
 *   -> getItemStack() -> ItemStack
 *   -> getNamespacedID() -> String
 */
public class CustomStack {

    private final MockItem delegate;

    public CustomStack(MockItem delegate) {
        this.delegate = delegate;
    }

    public static Map<String, CustomStack> getItems() {
        Map<String, CustomStack> result = new LinkedHashMap<>();
        for (Map.Entry<String, MockItem> entry : mock.MockItemsAdder.getItems().entrySet()) {
            result.put(entry.getKey(), new CustomStack(entry.getValue()));
        }
        return result;
    }

    public String getNamespacedID() {
        return delegate.getNamespacedID();
    }

    public ItemStack getItemStack() {
        return delegate.getItemStack();
    }
}

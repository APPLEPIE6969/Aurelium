package dev.lone.itemsadder.api;

import mock.CustomStack;

import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Shim class at the real ItemsAdder API path.
 * Delegates to mock.CustomStack which has the actual data.
 */
public class CustomStack {

    private final CustomStack delegate;

    private CustomStack(CustomStack delegate) {
        this.delegate = delegate;
    }

    public static Map<String, CustomStack> getItems() {
        Map<String, CustomStack> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, mock.CustomStack> entry : mock.MockItemsAdder.getItems().entrySet()) {
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

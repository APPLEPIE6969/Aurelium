package com.ssomar.score.api.executableitems;

import mock.MockEIItem;

import org.bukkit.inventory.ItemStack;

/**
 * Shim for individual ExecutableItem objects.
 * Scanner calls: getId() + buildItem()
 */
public class ExecutableItemInterface {

    private final MockEIItem delegate;

    public ExecutableItemInterface(MockEIItem delegate) {
        this.delegate = delegate;
    }

    public String getId() {
        return delegate.getId();
    }

    public ItemStack buildItem() {
        return delegate.buildItem();
    }
}

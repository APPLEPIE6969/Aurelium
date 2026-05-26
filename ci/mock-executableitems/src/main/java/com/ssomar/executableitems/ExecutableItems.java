package com.ssomar.executableitems;

import mock.MockEIItem;
import mock.MockExecutableItems;

import java.util.Collection;

/**
 * Legacy fallback shim for older EI versions.
 * Scanner calls: Class.forName("com.ssomar.executableitems.ExecutableItems")
 *   -> getPlugin() -> getItemManager() -> getAllItems()
 *   -> each item: getId() + buildItem(int)
 */
public class ExecutableItems {

    public static ExecutableItems getPlugin() {
        return new ExecutableItems();
    }

    public ItemManager getItemManager() {
        return new ItemManager();
    }

    public static class ItemManager {
        public Collection<MockEIItem> getAllItems() {
            return MockExecutableItems.getAllItems();
        }
    }
}

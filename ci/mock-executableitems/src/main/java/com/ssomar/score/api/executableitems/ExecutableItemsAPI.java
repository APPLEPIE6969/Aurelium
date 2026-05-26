package com.ssomar.score.api.executableitems;

import mock.MockExecutableItems;

/**
 * Shim at the real SCore API path that Aurelium's scanner reflects into.
 * Scanner calls: Class.forName("com.ssomar.score.api.executableitems.ExecutableItemsAPI")
 *   -> getInstance() -> getExecutableItemsManager()
 *   -> manager.getAllExecutableItems() -> Collection of items with getId() + buildItem()
 */
public class ExecutableItemsAPI {

    public static ExecutableItemsAPI getInstance() {
        return new ExecutableItemsAPI();
    }

    public ExecutableItemsManagerInterface getExecutableItemsManager() {
        return new ExecutableItemsManagerInterface();
    }
}

package com.ssomar.score.api.executableitems;

import mock.MockEIItem;
import mock.MockExecutableItems;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Shim for the ExecutableItems manager interface.
 * Scanner calls: getAllExecutableItems() -> Collection<ExecutableItemInterface>
 */
public class ExecutableItemsManagerInterface {

    public Collection<ExecutableItemInterface> getAllExecutableItems() {
        Collection<ExecutableItemInterface> result = new ArrayList<>();
        for (MockEIItem item : MockExecutableItems.getAllItems()) {
            result.add(new ExecutableItemInterface(item));
        }
        return result;
    }
}

package com.sucy.sxitem;

import mock.MockSXItem;
import mock.SXItemData;

import java.util.Collection;
import java.util.List;

public class SXItemManager {

 public Collection<SXItemData> getItemList() {
 return MockSXItem.getItemList();
 }
}

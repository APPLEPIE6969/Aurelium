package com.sucy.sxitem;

import mock.MockSXItem;

public class SXItem {

 private static final SXItem instance = new SXItem();

 public static SXItem getInst() {
 return instance;
 }

 public SXItemManager getItemManager() {
 return new SXItemManager();
 }
}

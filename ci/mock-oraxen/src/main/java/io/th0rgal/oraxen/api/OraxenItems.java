package io.th0rgal.oraxen.api;

import mock.MockOraxen;
import mock.MockOraxenItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

public class OraxenItems {

 public static OraxenItem getItemById(String id) {
 MockOraxenItem item = MockOraxen.getItems().get(id);
 if (item == null) return null;
 return new OraxenItem(item.getNamespacedId(), item.getItemStack());
 }

 public static Collection<OraxenItem> getItems() {
 Collection<OraxenItem> result = new ArrayList<>();
 for (Map.Entry<String, MockOraxenItem> entry : MockOraxen.getItems().entrySet()) {
 result.add(new OraxenItem(entry.getValue().getNamespacedId(), entry.getValue().getItemStack()));
 }
 return result;
 }

 public static String getIdByItem(ItemStack item) {
 if (item == null) return null;
 for (MockOraxenItem mi : MockOraxen.getItems().values()) {
 if (mi.getItemStack().isSimilar(item)) return mi.getNamespacedId();
 }
 return null;
 }
}

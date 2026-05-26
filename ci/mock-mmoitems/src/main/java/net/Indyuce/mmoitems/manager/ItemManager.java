package net.Indyuce.mmoitems.manager;

import mock.MockMMOItem;
import mock.MockMMOItems;
import net.Indyuce.mmoitems.api.MMOItem;
import net.Indyuce.mmoitems.api.Type;

import java.util.*;

public class ItemManager {

 public Collection<Type> getAll() {
 Set<String> typeNames = new LinkedHashSet<>();
 for (String key : MockMMOItems.getItems().keySet()) {
 typeNames.add(key.split(":")[0]);
 }
 Collection<Type> types = new ArrayList<>();
 for (String typeName : typeNames) {
 types.add(new Type(typeName));
 }
 return types;
 }

 public Map<String, MMOItem> getAll(Type type) {
 Map<String, MMOItem> result = new LinkedHashMap<>();
 for (MockMMOItem item : MockMMOItems.getItems().values()) {
 if (item.getType().equals(type.getId())) {
 result.put(item.getId(), new MMOItem(item.getItemStack()));
 }
 }
 return result;
 }
}

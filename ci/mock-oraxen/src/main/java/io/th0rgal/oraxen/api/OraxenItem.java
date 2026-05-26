package io.th0rgal.oraxen.api;

import org.bukkit.inventory.ItemStack;

public class OraxenItem {

 private final String id;
 private final ItemStack itemStack;

 public OraxenItem(String id, ItemStack itemStack) {
 this.id = id;
 this.itemStack = itemStack;
 }

 public String getId() {
 return id;
 }

 public ItemStack build() {
 return itemStack.clone();
 }
}

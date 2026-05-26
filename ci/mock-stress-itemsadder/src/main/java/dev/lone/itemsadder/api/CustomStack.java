package dev.lone.itemsadder.api;

import mock.MockItem;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class CustomStack {

 private final MockItem delegate;

 public CustomStack(MockItem delegate) {
 this.delegate = delegate;
 }

 public static Map<String, CustomStack> getItems() {
 Map<String, CustomStack> result = new LinkedHashMap<>();
 for (Map.Entry<String, MockItem> entry : mock.MockStressItemsAdder.getItems().entrySet()) {
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

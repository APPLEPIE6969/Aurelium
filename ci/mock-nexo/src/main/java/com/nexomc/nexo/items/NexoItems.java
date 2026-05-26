package com.nexomc.nexo.items;

import mock.MockNexo;

import java.util.Map;

import org.bukkit.inventory.ItemStack;

public class NexoItems {

 public static NexoItem itemFromId(String id) {
 ItemStack item = MockNexo.getItems().get(id);
 if (item == null) return null;
 return new NexoItem(id, item);
 }

 public static NexoItem getByItem(ItemStack item) {
 if (item == null) return null;
 for (Map.Entry<String, ItemStack> entry : MockNexo.getItems().entrySet()) {
 if (entry.getValue().isSimilar(item)) {
 return new NexoItem(entry.getKey(), entry.getValue());
 }
 }
 return null;
 }

 public static ItemStack getItemStack(String id) {
 NexoItem nexoItem = itemFromId(id);
 return nexoItem != null ? nexoItem.getBuilder().build() : null;
 }
}

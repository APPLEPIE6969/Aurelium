package com.nexomc.nexo.items;

import org.bukkit.inventory.ItemStack;

public class NexoItem {

 private final String id;
 private final ItemStack itemStack;

 public NexoItem(String id, ItemStack itemStack) {
 this.id = id;
 this.itemStack = itemStack;
 }

 public String getId() {
 return id;
 }

 public NexoItemStackBuilder getBuilder() {
 return new NexoItemStackBuilder(itemStack);
 }
}

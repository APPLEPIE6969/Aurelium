package com.nexomc.nexo.items;

import org.bukkit.inventory.ItemStack;

public class NexoItemStackBuilder {

 private final ItemStack itemStack;

 public NexoItemStackBuilder(ItemStack itemStack) {
 this.itemStack = itemStack;
 }

 public ItemStack build() {
 return itemStack.clone();
 }
}

package net.Indyuce.mmoitems.api;

import org.bukkit.inventory.ItemStack;

public class MMOItem {

 private final ItemStack itemStack;

 public MMOItem(ItemStack itemStack) {
 this.itemStack = itemStack;
 }

 public ItemStack newBuilder() {
 return itemStack.clone();
 }
}

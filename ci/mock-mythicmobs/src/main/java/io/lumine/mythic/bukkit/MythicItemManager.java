package io.lumine.mythic.bukkit;

import mock.MockMythicMobs;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.bukkit.inventory.ItemStack;

public class MythicItemManager {

 public Collection<String> getItemNames() {
 return MockMythicMobs.getItems().keySet();
 }

 public Optional<ItemStack> getItemStack(String id) {
 ItemStack item = MockMythicMobs.getItems().get(id);
 return Optional.ofNullable(item != null ? item.clone() : null);
 }
}

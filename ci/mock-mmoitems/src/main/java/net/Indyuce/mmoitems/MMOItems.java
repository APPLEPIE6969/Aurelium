package net.Indyuce.mmoitems;

import mock.MockMMOItems;
import net.Indyuce.mmoitems.api.MMOItem;
import net.Indyuce.mmoitems.api.Type;
import net.Indyuce.mmoitems.manager.ItemManager;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class MMOItems {

 public static MMOItems plugin;

 public MMOItems() {
 plugin = this;
 }

 public static MMOItems getPlugin() {
 if (plugin == null) {
 plugin = new MMOItems();
 }
 return plugin;
 }

 public ItemManager getItems() {
 return new ItemManager();
 }
}

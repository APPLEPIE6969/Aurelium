package io.lumine.mythic.bukkit;

public class MythicBukkit {

 private static final MythicBukkit instance = new MythicBukkit();
 private final MythicItemManager itemManager = new MythicItemManager();

 public static MythicBukkit inst() {
 return instance;
 }

 public MythicItemManager getItemManager() {
 return itemManager;
 }
}

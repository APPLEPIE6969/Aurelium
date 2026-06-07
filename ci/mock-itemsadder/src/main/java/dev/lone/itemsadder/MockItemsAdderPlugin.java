package dev.lone.itemsadder;

import dev.lone.itemsadder.api.CustomStack;
import org.bukkit.plugin.java.JavaPlugin;

public class MockItemsAdderPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // Register default items
        CustomStack.registerDefaults();
        getLogger().info("MockItemsAdder enabled with " + CustomStack.getItems().size() + " items!");
    }

    @Override
    public void onDisable() {
        getLogger().info("MockItemsAdder disabled!");
    }
}
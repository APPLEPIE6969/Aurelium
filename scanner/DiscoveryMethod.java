package com.aureleconomy.scanner;

/**
 * Enum representing the different methods by which a custom item can be discovered.
 * Each method has a human-readable display name for use in GUIs and commands.
 */
public enum DiscoveryMethod {

    PLUGIN_API_ITEMSADDER("ItemsAdder API"),
    PLUGIN_API_ORAXEN("Oraxen API"),
    PLUGIN_API_MMOITEMS("MMOItems API"),
    PLUGIN_API_MYTHICMOBS("MythicMobs API"),
    PLUGIN_API_EXECUTABLE_ITEMS("ExecutableItems API"),
    PLUGIN_API_NEXO("Nexo API"),
    PLUGIN_API_SX_ITEM("SX-Item API"),
    PDC_SCAN("PDC Scan"),
    CUSTOM_MODEL_DATA("CustomModelData"),
    LORE_PATTERN("Lore Pattern"),
    INVENTORY_SCAN("Inventory Scan"),
    INTERACTION_DETECT("Player Interaction");

    private final String displayName;

    DiscoveryMethod(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

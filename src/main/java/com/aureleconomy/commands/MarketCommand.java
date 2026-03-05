package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import com.aureleconomy.gui.MarketGUI;
import com.aureleconomy.gui.ShopGUI;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.TabExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import java.util.Collections;
import java.util.List;

public class MarketCommand implements TabExecutor {

    private final AurelEconomy plugin;

    public MarketCommand(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use the market."));
            return true;
        }

        if (!plugin.getConfig().getBoolean("market.enabled", true)) {
            player.sendMessage(Component.text("The market is currently disabled.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }

        String guiMode = plugin.getConfig().getString("market.gui-mode", "modern").toLowerCase();

        switch (guiMode) {
            case "classic" -> new MarketGUI(plugin, player).open(player);
            default -> new ShopGUI(plugin, player).open(player);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            @NotNull String[] args) {
        return Collections.emptyList();
    }
}

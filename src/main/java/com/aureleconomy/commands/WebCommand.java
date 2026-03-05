package com.aureleconomy.commands;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/**
 * /web command — generates a clickable session link to the web dashboard.
 * Supports both "local" (embedded HTTP server) and "cloud" (Render) modes.
 */
public class WebCommand implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final AurelEconomy plugin;

    public WebCommand(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use the web dashboard.", NamedTextColor.RED));
            return true;
        }

        if (!plugin.getConfig().getBoolean("web.enabled", false)) {
            player.sendMessage(MM.deserialize("<red>The web dashboard is not enabled on this server.</red>"));
            return true;
        }

        String webMode = plugin.getConfig().getString("web.mode", "cloud").toLowerCase();
        String url;

        if ("local".equals(webMode)) {
            if (plugin.getWebServer() == null || !plugin.getWebServer().isRunning()) {
                player.sendMessage(MM.deserialize("<red>Web server failed to start. Check the server console.</red>"));
                return true;
            }
            String token = plugin.getWebServer().getSessionManager().createSession(player.getUniqueId());
            String host = plugin.getConfig().getString("web.local.host", "localhost");
            int port = plugin.getWebServer().getPort();
            url = "http://" + host + ":" + port + "/?token=" + token;
        } else {
            if (plugin.getCloudSync() == null || !plugin.getCloudSync().isRegistered()) {
                player.sendMessage(MM.deserialize(
                        "<red>Cloud dashboard is not connected yet. Please wait a moment and try again.</red>"));
                return true;
            }
            url = plugin.getCloudSync().createSessionUrl(player);
        }

        // Send link immediately — frontend handles loading/retry
        player.sendMessage(Component.empty());
        player.sendMessage(
                MM.deserialize("<gradient:gold:yellow><bold>  Server Market — Web Dashboard</bold></gradient>"));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("  ").append(
                Component.text("▸ Click here to open the dashboard")
                        .color(NamedTextColor.GREEN)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))));
        player.sendMessage(Component.empty());
        player.sendMessage(MM.deserialize("<gray>  Session expires after 1 hour of inactivity.</gray>"));
        player.sendMessage(Component.empty());

        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
            @NotNull String label, @NotNull String[] args) {
        return Collections.emptyList();
    }
}

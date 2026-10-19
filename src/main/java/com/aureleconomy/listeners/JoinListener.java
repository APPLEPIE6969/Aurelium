package com.aureleconomy.listeners;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.sql.Connection;

public class JoinListener implements Listener {

    private final AurelEconomy plugin;

    public JoinListener(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        plugin.getEconomyManager().invalidateCache(uuid);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean hasEarnings = false;
            try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement("SELECT uuid, amount, item_display, timestamp FROM offline_earnings WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();

                while (rs.next()) {
                    hasEarnings = true;
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String itemDisplay = rs.getString("item_display");

                    event.getPlayer().sendMessage(Component.text("You earned ", NamedTextColor.GREEN)
                            .append(Component.text(plugin.getEconomyManager().getFormattedWithSymbol(amount, plugin.getEconomyManager().getDefaultCurrency()), NamedTextColor.GOLD))
                            .append(Component.text(" for selling ", NamedTextColor.GREEN))
                            .append(Component.text(itemDisplay, NamedTextColor.AQUA))
                            .append(Component.text(" while you were offline.", NamedTextColor.GREEN)));

                }

            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in JoinListener", e);
            }

            // The delete below borrows its own connection, and the SQLite pool
            // holds one, so it runs after the read is returned rather than nested
            // inside it.
            if (hasEarnings) {
                deleteAllRecordsForUUID(uuid);

                event.getPlayer().sendMessage(
                        Component.text("--------------------------------------------------", NamedTextColor.GOLD));
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Invalidate web session on disconnect for security
        if (plugin.getWebServer() != null && plugin.getWebServer().getSessionManager() != null) {
            plugin.getWebServer().getSessionManager().invalidate(event.getPlayer().getUniqueId());
        }
    }

    private void deleteAllRecordsForUUID(UUID uuid) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
        PreparedStatement ps = conn.prepareStatement("DELETE FROM offline_earnings WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getComponentLogger().error("Database error while deleting offline earnings for " + uuid, e);
        }
    }
}

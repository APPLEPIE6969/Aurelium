package com.aureleconomy.listeners;

import com.aureleconomy.AurelEconomy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class JoinListener implements Listener {

    private final AurelEconomy plugin;

    public JoinListener(AurelEconomy plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                    .prepareStatement("SELECT * FROM offline_earnings WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ResultSet rs = ps.executeQuery();

                boolean hasEarnings = false;
                while (rs.next()) {
                    hasEarnings = true;
                    int id = rs.getInt("id");
                    double amount = rs.getDouble("amount");
                    String itemDisplay = rs.getString("item_display");

                    // Send message
                    event.getPlayer().sendMessage(Component.text("You earned ", NamedTextColor.GREEN)
                            .append(Component.text(plugin.getEconomyManager().format(amount), NamedTextColor.GOLD))
                            .append(Component.text(" for selling ", NamedTextColor.GREEN))
                            .append(Component.text(itemDisplay, NamedTextColor.AQUA))
                            .append(Component.text(" while you were offline.", NamedTextColor.GREEN)));

                    // Delete record
                    deleteRecord(id);
                }

                if (hasEarnings) {
                    event.getPlayer().sendMessage(
                            Component.text("--------------------------------------------------", NamedTextColor.GOLD));
                }

            } catch (SQLException e) {
                plugin.getComponentLogger().error("Database error in JoinListener", e);
            }
        });
    }

    private void deleteRecord(int id) {
        try (PreparedStatement ps = plugin.getDatabaseManager().getConnection()
                .prepareStatement("DELETE FROM offline_earnings WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}

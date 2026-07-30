package com.aureleconomy.database.repositories;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.impl.Database;
import com.aureleconomy.database.schema.Table;

/**
 * Access to the {@code players} table.
 */
public class PlayerRepository extends Repository {

    public PlayerRepository(Database database) {
        super(database);
    }

    public Optional<String> findName(UUID uuid) {
        return findColumn(uuid, "name");
    }

    public Optional<String> findGuiStyle(UUID uuid) {
        return findColumn(uuid, "gui_style");
    }

    /**
     * Inserts the player, or refreshes the stored name if the row already exists. Leaves
     * gui_style untouched.
     */
    public void upsert(UUID uuid, String name) throws SQLException {
        String sql = database.getType() == DatabaseType.MYSQL
                ? "INSERT INTO " + Table.PLAYERS + " (uuid, name) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE name = VALUES(name)"
                : "INSERT INTO " + Table.PLAYERS + " (uuid, name) VALUES (?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name";

        try (PreparedStatement ps = database.getConnection().prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    public void setGuiStyle(UUID uuid, String style) throws SQLException {
        try (PreparedStatement ps = database.getConnection()
                .prepareStatement("UPDATE " + Table.PLAYERS + " SET gui_style = ? WHERE uuid = ?")) {
            ps.setString(1, style);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    private Optional<String> findColumn(UUID uuid, String column) {
        try (PreparedStatement ps = database.getConnection()
                .prepareStatement("SELECT " + column + " FROM " + Table.PLAYERS + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.ofNullable(rs.getString(column));
                }
            }
        } catch (SQLException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}

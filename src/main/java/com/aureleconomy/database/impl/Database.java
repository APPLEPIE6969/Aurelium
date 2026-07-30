package com.aureleconomy.database.impl;

import java.sql.Connection;
import java.sql.SQLException;

import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.types.SQLTypes;

public interface Database {

    /** Opens the connection. A no-op if one is already open. */
    void connect() throws SQLException;

    /** Returns the live connection, reconnecting first if it was closed or never opened. */
    Connection getConnection() throws SQLException;

    /** Executes a statement. Failures propagate — callers decide whether they are fatal. */
    void execute(String sql) throws SQLException;

    boolean isConnected();

    void disconnect() throws SQLException;

    SQLTypes getTypes();

    DatabaseType getType();
}

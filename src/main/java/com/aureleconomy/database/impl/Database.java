package com.aureleconomy.database.impl;

import java.sql.Connection;
import java.sql.SQLException;

import com.aureleconomy.database.DatabaseType;
import com.aureleconomy.database.types.SQLTypes;

public interface Database {

    /** Opens the pool. A no-op if it is already open. */
    void connect() throws SQLException;

    /**
     * Borrows a connection from the pool, opening the pool first if it was closed or
     * never opened. The caller owns the borrow: closing it returns it to the pool,
     * and leaving it unclosed leaks a slot until the pool times out.
     */
    Connection getConnection() throws SQLException;

    /** Executes a statement on its own borrow. Failures propagate, and callers decide whether they are fatal. */
    void execute(String sql) throws SQLException;

    boolean isConnected();

    void disconnect() throws SQLException;

    SQLTypes getTypes();

    DatabaseType getType();
}

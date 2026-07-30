package com.aureleconomy.database.repositories;

import com.aureleconomy.database.impl.Database;

public abstract class Repository {

    protected final Database database;

    public Repository(Database database) {
        this.database = database;
    }
}

package net.indyuce.mmoitems.api;

public class Type {
    private final String id;
    private final String category;

    public Type(String id, String category) {
        this.id = id;
        this.category = category;
    }

    public String getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }
}
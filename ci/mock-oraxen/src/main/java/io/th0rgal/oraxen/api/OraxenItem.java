package io.th0rgal.oraxen.api;

public class OraxenItem {
    private final String id;
    private final String material;

    public OraxenItem(String id, String material) {
        this.id = id;
        this.material = material;
    }

    public String getId() {
        return id;
    }

    public String getMaterial() {
        return material;
    }
}
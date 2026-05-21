package com.aureleconomy.scanner;

/**
 * Result of a registration attempt in CustomItemRegistry.
 * Indicates whether the item was newly registered or was a duplicate prevented.
 */
public class RegistrationResult {

    private final String canonicalId;
    private final boolean isNew;
    private final DiscoveryMethod method;

    private RegistrationResult(String canonicalId, boolean isNew, DiscoveryMethod method) {
        this.canonicalId = canonicalId;
        this.isNew = isNew;
        this.method = method;
    }

    public static RegistrationResult newlyRegistered(String id, DiscoveryMethod method) {
        return new RegistrationResult(id, true, method);
    }

    public static RegistrationResult alreadyExists(String id, DiscoveryMethod method) {
        return new RegistrationResult(id, false, method);
    }

    public String getCanonicalId() { return canonicalId; }
    public boolean isNew() { return isNew; }
    public boolean isDuplicate() { return !isNew; }
    public DiscoveryMethod getMethod() { return method; }
}

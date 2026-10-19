package org.quarkpress.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A role: its key (exact, case-sensitive), the display name stored in English, and its capabilities in order. A
 * capability mapped to false is a denial for users holding the role.
 */
public record RoleDefinition(String key, String displayName, Map<String, Boolean> capabilities) {

    public RoleDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(capabilities, "capabilities");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("a role key must not be empty");
        }
        Map<String, Boolean> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : capabilities.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new NullPointerException("a role's capabilities hold no null");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        capabilities = Collections.unmodifiableMap(copy);
    }

    /** A role granting every capability in {@code granted}. */
    public static RoleDefinition of(String key, String displayName, List<String> granted) {
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        for (String capability : granted) {
            capabilities.put(Objects.requireNonNull(capability, "capability"), true);
        }
        return new RoleDefinition(key, displayName, capabilities);
    }

    /** This role with {@code capability} set to {@code grant}: in place if present, appended otherwise. */
    public RoleDefinition withCapability(String capability, boolean grant) {
        Map<String, Boolean> changed = new LinkedHashMap<>(capabilities);
        changed.put(Objects.requireNonNull(capability, "capability"), grant);
        return new RoleDefinition(key, displayName, changed);
    }

    /** This role without {@code capability}. */
    public RoleDefinition withoutCapability(String capability) {
        Map<String, Boolean> changed = new LinkedHashMap<>(capabilities);
        changed.remove(capability);
        return new RoleDefinition(key, displayName, changed);
    }
}

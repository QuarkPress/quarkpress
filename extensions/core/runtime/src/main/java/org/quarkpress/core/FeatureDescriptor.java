package org.quarkpress.core;

import java.util.Objects;

/**
 * A per-site feature that an installed module declares.
 *
 * @param key 1 to 64 characters from {@code a-z}, {@code 0-9}, {@code -} and {@code _}, starting with a letter
 * @param module the declaring module, for messages and tools
 * @param defaultEnabled the value used when neither the site nor the network says otherwise
 * @param description one line for tools
 */
public record FeatureDescriptor(String key, String module, boolean defaultEnabled, String description) {

    private static final int MAX_KEY_LENGTH = 64;

    public FeatureDescriptor {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(description, "description");
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("invalid feature key '" + key
                    + "': use 1 to 64 characters a-z, 0-9, '-' or '_', starting with a letter");
        }
    }

    public static boolean isValidKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            return false;
        }
        char first = key.charAt(0);
        if (first < 'a' || first > 'z') {
            return false;
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}

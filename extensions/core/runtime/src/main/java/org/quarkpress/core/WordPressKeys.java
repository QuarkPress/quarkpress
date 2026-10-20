package org.quarkpress.core;

import java.util.Objects;

/** WordPress's key sanitizing, used for post type, post status and comment status names. */
public final class WordPressKeys {

    private WordPressKeys() {}

    /**
     * WordPress's {@code sanitize_key}: ASCII letters lower-cased, then every character other than {@code a-z},
     * {@code 0-9}, {@code _} and {@code -} removed.
     */
    public static String sanitize(String key) {
        Objects.requireNonNull(key, "key");
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + ('a' - 'A'));
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }
}

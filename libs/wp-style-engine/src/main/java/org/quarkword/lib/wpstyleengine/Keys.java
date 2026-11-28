package org.quarkword.lib.wpstyleengine;

/** WordPress's {@code sanitize_key}: ASCII letters lowercased, then only {@code a-z 0-9 _ -} kept. */
final class Keys {

    private Keys() {}

    static String sanitizeKey(String key) {
        StringBuilder out = null;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (keep && out == null) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(key.length());
                out.append(key, 0, i);
            }
            if (keep) {
                out.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                out.append((char) (c + ('a' - 'A')));
            }
            // anything else, multibyte characters included, is dropped
        }
        return out == null ? key : out.toString();
    }
}

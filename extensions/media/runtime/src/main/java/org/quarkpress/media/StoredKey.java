package org.quarkpress.media;

/**
 * The rule every storage key follows. A key is a relative path of {@code /}-separated segments, such as
 * {@code sites/2/2026/11/photo.jpg}: not empty, at most {@value #MAX_BYTES} bytes in UTF-8, no leading {@code /}, no
 * empty segment (so no trailing or doubled {@code /}), no {@code .} or {@code ..} segment, no {@code \}, no NUL or
 * other control character below U+0020, and no unpaired surrogate. Storage backends check every key with
 * {@link #require} before touching their storage.
 */
public final class StoredKey {

    /** The longest key, in UTF-8 bytes. */
    public static final int MAX_BYTES = 1024;

    private StoredKey() {}

    /**
     * Returns {@code key} when it is a valid storage key.
     *
     * @throws IllegalArgumentException when it is not, naming the reason
     */
    public static String require(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("a storage key is not empty");
        }
        int bytes = 0;
        int segmentStart = 0;
        int length = key.length();
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (c < 0x20) {
                throw new IllegalArgumentException("a storage key holds no control character");
            }
            if (c == '\\') {
                throw new IllegalArgumentException("a storage key holds no backslash: " + key);
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(key.charAt(i + 1))) {
                    throw new IllegalArgumentException("a storage key holds no unpaired surrogate");
                }
                bytes += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("a storage key holds no unpaired surrogate");
            } else if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else {
                bytes += 3;
            }
            if (c == '/') {
                checkSegment(key, segmentStart, i);
                segmentStart = i + 1;
            }
        }
        checkSegment(key, segmentStart, length);
        if (bytes > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "a storage key is at most " + MAX_BYTES + " bytes in UTF-8, not " + bytes);
        }
        return key;
    }

    private static void checkSegment(String key, int start, int end) {
        int length = end - start;
        if (length == 0) {
            throw new IllegalArgumentException("a storage key has no empty segment: " + key);
        }
        if (key.charAt(start) == '.' && (length == 1 || (length == 2 && key.charAt(start + 1) == '.'))) {
            throw new IllegalArgumentException("a storage key has no . or .. segment: " + key);
        }
    }
}

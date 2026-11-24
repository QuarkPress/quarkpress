package org.quarkword.front.runtime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * QuarkWord's bounds on a front-end request before anything is parsed or read: a path of at most 2,048 bytes and 32
 * segments, percent-encoding that is well formed ({@code %} followed by two hexadecimal digits, the decoded bytes
 * valid UTF-8 without NUL) in the path and the query string, no {@code ..} segment, and at most 64 query parameters.
 * A request outside them is answered 404 without a query.
 */
public final class RequestLimits {

    public static final int MAX_PATH_BYTES = 2048;
    public static final int MAX_SEGMENTS = 32;
    public static final int MAX_QUERY_PARAMETERS = 64;

    private RequestLimits() {}

    /** Whether the raw request path and raw query string (null or empty for none) are within the limits. */
    public static boolean check(String rawPath, String rawQuery) {
        if (rawPath == null) {
            return false;
        }
        if (rawPath.getBytes(StandardCharsets.UTF_8).length > MAX_PATH_BYTES) {
            return false;
        }
        if (segments(rawPath) > MAX_SEGMENTS) {
            return false;
        }
        if (hasDotDotSegment(rawPath) || !wellEncoded(rawPath)) {
            return false;
        }
        if (rawQuery != null && !rawQuery.isEmpty()) {
            if (parameters(rawQuery) > MAX_QUERY_PARAMETERS || !wellEncoded(rawQuery)) {
                return false;
            }
        }
        return true;
    }

    private static int segments(String path) {
        int count = 0;
        int length = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                if (length > 0) {
                    count++;
                }
                length = 0;
            } else {
                length++;
            }
        }
        return length > 0 ? count + 1 : count;
    }

    /** Whether a segment is {@code ..}, raw or with either dot percent-encoded. */
    private static boolean hasDotDotSegment(String path) {
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                if (isDotDot(path, start, i)) {
                    return true;
                }
                start = i + 1;
            }
        }
        return false;
    }

    private static boolean isDotDot(String path, int start, int end) {
        int dots = 0;
        int i = start;
        while (i < end) {
            if (path.charAt(i) == '.') {
                i++;
            } else if (end - i >= 3
                    && path.charAt(i) == '%'
                    && path.charAt(i + 1) == '2'
                    && (path.charAt(i + 2) == 'E' || path.charAt(i + 2) == 'e')) {
                i += 3;
            } else {
                return false;
            }
            dots++;
        }
        return dots == 2;
    }

    private static int parameters(String query) {
        int count = 0;
        int length = 0;
        for (int i = 0; i < query.length(); i++) {
            if (query.charAt(i) == '&') {
                if (length > 0) {
                    count++;
                }
                length = 0;
            } else {
                length++;
            }
        }
        return length > 0 ? count + 1 : count;
    }

    /**
     * Whether every {@code %} starts a two-digit hexadecimal escape, no character or decoded byte is NUL, and the
     * decoded bytes are valid UTF-8 (so no overlong sequence and no encoded surrogate).
     */
    static boolean wellEncoded(String text) {
        ByteArrayOutputStream decoded = new ByteArrayOutputStream(text.length());
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < raw.length; i++) {
            byte b = raw[i];
            if (b == 0) {
                return false;
            }
            if (b == '%') {
                if (i + 2 >= raw.length || hex(raw[i + 1]) < 0 || hex(raw[i + 2]) < 0) {
                    return false;
                }
                int value = hex(raw[i + 1]) * 16 + hex(raw[i + 2]);
                if (value == 0) {
                    return false;
                }
                decoded.write(value);
                i += 2;
            } else {
                decoded.write(b);
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded.toByteArray()));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') {
            return b - '0';
        }
        if (b >= 'a' && b <= 'f') {
            return b - 'a' + 10;
        }
        if (b >= 'A' && b <= 'F') {
            return b - 'A' + 10;
        }
        return -1;
    }
}

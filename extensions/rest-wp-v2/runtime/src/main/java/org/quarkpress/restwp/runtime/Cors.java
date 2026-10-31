package org.quarkpress.restwp.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-origin headers. WordPress answers every {@code Origin} with credentials; QuarkPress answers only the origins
 * the configuration lists, and gives those the wider header lists (the method override header may be sent, the
 * {@code Allow} and upload headers may be read). Any other origin gets no {@code Access-Control-Allow-Origin} and keeps
 * the fixed lists.
 */
public final class Cors {

    static final String METHODS = "OPTIONS, GET, POST, PUT, PATCH, DELETE";
    static final String EXPOSED = ResponseHeaders.EXPOSED + ", Allow, X-WP-Upload-Attachment-ID";
    static final String ALLOWED = ResponseHeaders.ALLOWED + ", X-HTTP-Method-Override";

    private Cors() {}

    /**
     * The cross-origin headers of an answer, to be set over the fixed ones.
     *
     * @param origin the request's {@code Origin} header (every value joined as PHP holds them); empty or blank counts as
     *     none
     * @param anonymousGet whether the request is a GET (its real method, not the override) of a caller who is not
     *     logged in, whose public answer caches may share between origins
     * @param configured the configured origins
     */
    public static Map<String, List<String>> headers(
            Optional<String> origin, boolean anonymousGet, List<String> configured) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        Optional<String> sent = origin.filter(value -> !value.isEmpty());
        Optional<String> allowed = sent.filter(value -> isConfigured(value, configured));
        if (allowed.isPresent()) {
            headers.put(ResponseHeaders.EXPOSE_HEADERS, List.of(EXPOSED));
            headers.put(ResponseHeaders.ALLOW_HEADERS, List.of(ALLOWED));
            headers.put("Access-Control-Allow-Origin", List.of(allowed.get()));
            headers.put("Access-Control-Allow-Methods", List.of(METHODS));
            headers.put("Access-Control-Allow-Credentials", List.of("true"));
            headers.put("Vary", List.of("Origin"));
        } else if (anonymousGet || (sent.isPresent() && hasUsable(configured))) {
            // WordPress's rule for public reads, and an answer that depends on the origin whenever some are allowed
            headers.put("Vary", List.of("Origin"));
        }
        return headers;
    }

    /**
     * Whether {@code origin} is one of the configured origins: the same characters, ASCII letters compared ignoring
     * case, and only printable ASCII without spaces or commas (a list of origins, a line break or a non-ASCII look-alike
     * never matches). The answer echoes the request's origin, which is then made of those characters only.
     */
    static boolean isConfigured(String origin, List<String> configured) {
        if (!isToken(origin)) {
            return false;
        }
        for (String entry : configured) {
            String candidate = entry.strip();
            if (isToken(candidate) && equalsIgnoringAsciiCase(origin, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsable(List<String> configured) {
        for (String entry : configured) {
            if (isToken(entry.strip())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isToken(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c >= 0x7F || c == ',') {
                return false;
            }
        }
        return true;
    }

    static boolean equalsIgnoringAsciiCase(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            if (lower(a.charAt(i)) != lower(b.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }
}

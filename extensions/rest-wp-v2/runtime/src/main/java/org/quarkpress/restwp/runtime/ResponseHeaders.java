package org.quarkpress.restwp.runtime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The headers WordPress sends on every REST answer, its no-cache headers, and its whitespace rule for header values.
 */
public final class ResponseHeaders {

    static final String JSON_TYPE = "application/json; charset=UTF-8";

    static final String EXPOSE_HEADERS = "Access-Control-Expose-Headers";
    static final String ALLOW_HEADERS = "Access-Control-Allow-Headers";

    /** The response headers a browser script may read. */
    static final String EXPOSED = "X-WP-Total, X-WP-TotalPages, Link";

    /** The request headers a browser script may send. */
    static final String ALLOWED = "Authorization, X-WP-Nonce, Content-Disposition, Content-MD5, Content-Type";

    private ResponseHeaders() {}

    /**
     * The fixed headers, in WordPress's order: the JSON content type, {@code X-Robots-Tag}, the API root's
     * {@code Link}, {@code X-Content-Type-Options}, then the exposed and allowed header lists.
     *
     * @param apiRoot the site's API root, or null when the request's site is unknown: then no {@code Link} is sent,
     *     since the only address there is to name would be the one the client sent in its {@code Host} header
     */
    public static Map<String, List<String>> fixed(String apiRoot) {
        Map<String, List<String>> headers = refusal(JSON_TYPE, apiRoot);
        headers.put(EXPOSE_HEADERS, List.of(EXPOSED));
        headers.put(ALLOW_HEADERS, List.of(ALLOWED));
        return headers;
    }

    /**
     * The headers of WordPress's refusal of JSONP when it is disabled, which are those it has sent before refusing:
     * the JSON content type (the JavaScript one only goes with JSONP enabled), {@code X-Robots-Tag}, the API root's
     * {@code Link} (none for a null root) and {@code X-Content-Type-Options}, nothing else.
     */
    static Map<String, List<String>> jsonpRefusal(String apiRoot) {
        return refusal(JSON_TYPE, apiRoot);
    }

    private static Map<String, List<String>> refusal(String contentType, String apiRoot) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(contentType));
        headers.put("X-Robots-Tag", List.of("noindex"));
        if (apiRoot != null) {
            headers.put("Link", List.of("<" + apiRoot + ">; rel=\"https://api.w.org/\""));
        }
        headers.put("X-Content-Type-Options", List.of("nosniff"));
        return headers;
    }

    /** WordPress's no-cache headers. */
    public static Map<String, List<String>> noCache() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Expires", List.of("Wed, 11 Jan 1984 05:00:00 GMT"));
        headers.put("Cache-Control", List.of("no-cache, must-revalidate, max-age=0, no-store, private"));
        return headers;
    }

    /**
     * A header value as WordPress sends it: every run of whitespace (space, tab, line feed, vertical tab, form feed,
     * carriage return) replaced by one space, so no value can break the header block.
     */
    static String collapse(String value) {
        StringBuilder out = null;
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!isSpace(c)) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            int end = i + 1;
            while (end < length && isSpace(value.charAt(end))) {
                end++;
            }
            if (out == null) {
                if (c == ' ' && end == i + 1) {
                    continue;
                }
                out = new StringBuilder(length).append(value, 0, i);
            }
            out.append(' ');
            i = end - 1;
        }
        return out == null ? value : out.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }
}

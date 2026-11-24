package org.quarkword.front.runtime;

/** WordPress's {@code set_url_scheme}: the scheme a URL gets for a mode, given the request's and the admin's SSL. */
public final class UrlSchemes {

    private UrlSchemes() {}

    /**
     * {@code url} with the scheme of {@code mode}: {@code http}, {@code https} and {@code relative} are themselves;
     * {@code admin}, {@code login}, {@code login_post} and {@code rpc} give {@code https} when {@code ssl} or
     * {@code forceSslAdmin} holds, else {@code http}; any other mode, null included, gives {@code https} when
     * {@code ssl}, else {@code http}. The URL is trimmed and a protocol-relative {@code //host/...} is read as
     * {@code http}. Mode {@code relative} removes the scheme and host and leaves exactly one leading slash; the other
     * modes replace the leading {@code scheme://}, and a URL without one is left as it is.
     */
    public static String set(String url, String mode, boolean ssl, boolean forceSslAdmin) {
        String value = url.trim();
        if (value.startsWith("//")) {
            value = "http:" + value;
        }
        String scheme = scheme(mode, ssl, forceSslAdmin);
        int schemeEnd = schemeEnd(value);
        if (scheme.equals("relative")) {
            if (schemeEnd < 0) {
                return value.stripLeading();
            }
            int hostEnd = value.indexOf('/', schemeEnd + 3);
            String rest = hostEnd < 0 ? "" : value.substring(hostEnd);
            return rest.isEmpty() ? rest : "/" + withoutLeadingSlashes(rest);
        }
        return schemeEnd < 0 ? value : scheme + value.substring(schemeEnd);
    }

    private static String scheme(String mode, boolean ssl, boolean forceSslAdmin) {
        if (mode == null || mode.isEmpty()) {
            return ssl ? "https" : "http";
        }
        return switch (mode) {
            case "http", "https", "relative" -> mode;
            case "admin", "login", "login_post", "rpc" -> ssl || forceSslAdmin ? "https" : "http";
            default -> ssl ? "https" : "http";
        };
    }

    /** The index of {@code ://} when {@code value} starts with a scheme of word characters, else -1. */
    private static int schemeEnd(String value) {
        int separator = value.indexOf("://");
        if (separator <= 0) {
            return -1;
        }
        for (int i = 0; i < separator; i++) {
            char c = value.charAt(i);
            boolean word = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
            if (!word) {
                return -1;
            }
        }
        return separator;
    }

    private static String withoutLeadingSlashes(String path) {
        int start = 0;
        while (start < path.length() && (path.charAt(start) == '/' || path.charAt(start) == '\\')) {
            start++;
        }
        return path.substring(start);
    }
}

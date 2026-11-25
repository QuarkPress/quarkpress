package org.quarkword.front.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code Location} of a redirect as WordPress cleans it ({@code wp_sanitize_redirect}) and checks it before
 * redirecting a visitor to an address the request named ({@code wp_validate_redirect}).
 */
public final class RedirectUrls {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String ALLOWED_PUNCTUATION = "-~+_.?#=&;,/:%!*[]()@";

    private RedirectUrls() {}

    /**
     * {@code url} as a header can carry it: spaces as {@code %20}, every other character outside letters, digits and
     * {@code -~+_.?#=&;,/:%!*[]()@} deleted, except that a character beyond ASCII is kept as its UTF-8 bytes
     * percent-encoded (upper-case hex); then every {@code %0d} and {@code %0a}, in either case, removed until none is
     * left.
     */
    public static String sanitize(String url) {
        Objects.requireNonNull(url, "url");
        StringBuilder kept = new StringBuilder(url.length());
        int i = 0;
        while (i < url.length()) {
            int codePoint = url.codePointAt(i);
            int width = Character.charCount(codePoint);
            if (codePoint == ' ') {
                kept.append("%20");
            } else if (codePoint < 0x80) {
                char c = (char) codePoint;
                if ((c >= 'a' && c <= 'z')
                        || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9')
                        || ALLOWED_PUNCTUATION.indexOf(c) >= 0) {
                    kept.append(c);
                }
            } else if (!(codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE)) {
                for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
                    kept.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
                }
            }
            i += width;
        }
        return withoutLineBreaks(kept.toString());
    }

    /** {@code text} without {@code %0d}, {@code %0a}, {@code %0D} and {@code %0A}, removed until none is left. */
    private static String withoutLineBreaks(String text) {
        String current = text;
        while (true) {
            String next = current;
            for (String strip : new String[] {"%0d", "%0a", "%0D", "%0A"}) {
                next = next.replace(strip, "");
            }
            if (next.equals(current)) {
                return current;
            }
            current = next;
        }
    }

    /**
     * {@link #validate(String, String, String, String)} for a request at the site root.
     */
    public static String validate(String url, String homeHost, String fallback) {
        return validate(url, homeHost, "/", fallback);
    }

    /**
     * {@code url} sanitised when it may be redirected to, else {@code fallback}: a URL starting with {@code //} is
     * taken as {@code http:}; only {@code http} and {@code https} schemes; a relative path without a leading slash
     * (other than {@code 0}, which PHP reads as empty) is made absolute against the directory of {@code requestUri};
     * a URL with a scheme, user, password or port but no host, or with {@code :/?#@} in its user, password or host, is
     * refused; a host must be {@code homeHost} (or its lower-case form). A path starting with a slash is kept as it
     * is, relative to the host. A {@code null} fallback is answered as {@code null}.
     */
    public static String validate(String url, String homeHost, String requestUri, String fallback) {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(homeHost, "homeHost");
        Objects.requireNonNull(requestUri, "requestUri");
        String location = sanitize(trimControl(url));
        if (location.startsWith("//")) {
            location = "http:" + location;
        }
        int cut = location.indexOf('?');
        String test = cut > 0 ? location.substring(0, cut) : location;
        Optional<PhpUrls.Url> parsed = PhpUrls.parseUrl(test);
        if (parsed.isEmpty()) {
            return fallback;
        }
        PhpUrls.Url parts = parsed.get();
        if (parts.scheme != null && !(parts.scheme.equals("http") || parts.scheme.equals("https"))) {
            return fallback;
        }
        // PHP's empty(): a path of "0" is left as it is
        if (parts.host == null
                && parts.path != null
                && !parts.path.isEmpty()
                && !parts.path.equals("0")
                && parts.path.charAt(0) != '/') {
            String directory = directory(requestUri);
            location = "/" + PhpUrls.ltrim(directory + "/", '/') + location;
        }
        if (parts.host == null
                && (parts.scheme != null || parts.user != null || parts.pass != null || parts.port != null)) {
            return fallback;
        }
        for (String component : new String[] {parts.user, parts.pass, parts.host}) {
            if (component != null && containsAny(component, ":/?#@")) {
                return fallback;
            }
        }
        if (parts.host != null
                && !parts.host.equals(homeHost)
                && !homeHost.toLowerCase(Locale.ROOT).equals(parts.host)) {
            return fallback;
        }
        return location;
    }

    /** PHP's {@code dirname} of the request's path with a {@code ?} after it, backslashes as slashes. */
    private static String directory(String requestUri) {
        String path = PhpUrls.parseUrl("http://placeholder" + requestUri)
                .map(parts -> parts.path == null ? "" : parts.path)
                .orElse("");
        String withMark = path + "?";
        int slash = withMark.lastIndexOf('/');
        String directory;
        if (slash < 0) {
            directory = ".";
        } else if (slash == 0) {
            directory = "/";
        } else {
            directory = PhpUrls.rtrim(withMark.substring(0, slash), '/');
            if (directory.isEmpty()) {
                directory = "/";
            }
        }
        return directory.replace('\\', '/');
    }

    private static boolean containsAny(String text, String characters) {
        for (int i = 0; i < text.length(); i++) {
            if (characters.indexOf(text.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** {@code text} without leading and trailing spaces, tabs, line breaks, NUL, backspace and vertical tabs. */
    private static String trimControl(String text) {
        String trimmed = " \t\n\r\0\u0008\u000B";
        int start = 0;
        int end = text.length();
        while (start < end && trimmed.indexOf(text.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && trimmed.indexOf(text.charAt(end - 1)) >= 0) {
            end--;
        }
        return text.substring(start, end);
    }
}

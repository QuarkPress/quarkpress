package org.quarkpress.restwp.runtime;

import java.util.Optional;

/**
 * Which request paths are REST paths. A path is one when the configured base ({@code /wp-json} by default), or
 * {@code index.php/} and the base, follows the site's own path right away, and is followed by {@code /} or nothing.
 * The comparison is on the raw path, as the site was resolved from it: the site path ignores ASCII case, the base does
 * not, and no {@code //}, dot segment or encoded slash makes another prefix count.
 */
public final class RestPaths {

    private static final String INDEX = "index.php/";

    private static volatile String nonApplicationRoot = "/q/";

    private static volatile String entry = RestEntryResource.PATH;

    private RestPaths() {}

    /**
     * The route a REST path addresses, with its trailing slashes trimmed ({@code /} for the base itself), still
     * percent-encoded; empty when {@code requestPath} is not a REST path of the site at {@code sitePath}.
     *
     * @param sitePath the site's path, with leading and trailing slash
     * @param base the configured base, such as {@code /wp-json}
     */
    public static Optional<String> route(String requestPath, String sitePath, String base) {
        if (!startsWithIgnoringAsciiCase(requestPath, sitePath)) {
            return Optional.empty();
        }
        String below = requestPath.substring(sitePath.length());
        if (below.startsWith(INDEX)) {
            below = below.substring(INDEX.length());
        }
        String name = normalizeBase(base).substring(1);
        if (!below.startsWith(name)) {
            return Optional.empty();
        }
        if (below.length() == name.length()) {
            return Optional.of("/");
        }
        if (below.charAt(name.length()) != '/') {
            return Optional.empty();
        }
        return Optional.of(trimRoute(below.substring(name.length())));
    }

    /**
     * Whether {@code text} starts with {@code prefix}, ASCII letters compared ignoring case and every other character
     * exactly (a non-ASCII letter never stands for an ASCII one).
     */
    static boolean startsWithIgnoringAsciiCase(String text, String prefix) {
        if (text.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            char a = text.charAt(i);
            char b = prefix.charAt(i);
            if (a != b && lowerAscii(a) != lowerAscii(b)) {
                return false;
            }
        }
        return true;
    }

    private static char lowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    /** A route without its trailing slashes and backslashes; {@code /} when nothing is left. */
    public static String trimRoute(String route) {
        int end = route.length();
        while (end > 0 && (route.charAt(end - 1) == '/' || route.charAt(end - 1) == '\\')) {
            end--;
        }
        return end == 0 ? "/" : route.substring(0, end);
    }

    /**
     * The configured base with one leading slash and no trailing one.
     *
     * @throws IllegalArgumentException when nothing is left: the REST API cannot take every path of a site
     */
    public static String normalizeBase(String base) {
        String trimmed = RoutePattern.trimSlashes(base.strip());
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "quarkpress.rest-wp-v2.path must name a path below the site, not " + base);
        }
        return "/" + trimmed;
    }

    /**
     * The absolute path of the REST entry resource: {@link RestEntryResource#PATH} below the JAX-RS application path,
     * itself below the HTTP root path.
     *
     * @param httpRoot {@code quarkus.http.root-path}
     * @param applicationPath the path Quarkus REST deploys the application's resources under
     */
    public static String entryPath(String httpRoot, String applicationPath) {
        String path = join(join(httpRoot, applicationPath), RestEntryResource.PATH);
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('*') >= 0 || path.indexOf(':') >= 0) {
            throw new IllegalArgumentException("the REST entry path " + path + " (quarkus.http.root-path " + httpRoot
                    + ", REST application path " + applicationPath + ") cannot be re-routed to; use a plain path");
        }
        return path;
    }

    private static String join(String parent, String child) {
        String left = RoutePattern.trimSlashes(parent.strip());
        String right = RoutePattern.trimSlashes(child.strip());
        if (left.isEmpty()) {
            return "/" + right;
        }
        return right.isEmpty() ? "/" + left : "/" + left + "/" + right;
    }

    /** Called once by the extension's recorder with the path {@link #entryPath(String, String)} computed. */
    public static void installEntryPath(String path) {
        entry = path;
    }

    /** The absolute path REST requests are re-routed to. */
    public static String entryPath() {
        return entry;
    }

    /** Called once by the extension's recorder with Quarkus's non-application root path. */
    public static void installNonApplicationRoot(String path) {
        String leading = path.startsWith("/") ? path : "/" + path;
        int end = leading.length();
        while (end > 1 && leading.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = leading.substring(0, end);
        nonApplicationRoot = trimmed.equals("/") ? "" : trimmed + "/";
    }

    /** Whether a normalized request path lies under the non-application root ({@code /q/} by default). */
    public static boolean underNonApplicationRoot(String path) {
        String root = nonApplicationRoot;
        return !root.isEmpty() && (path + "/").startsWith(root);
    }
}

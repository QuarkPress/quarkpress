package org.quarkpress.multisite.runtime;

/**
 * The Quarkus non-application root ({@code /q/} by default, where health, metrics and the Dev UI live). Requests whose
 * normalized path (the path the router serves) lies under it are not site-resolved, so those endpoints never depend on
 * the site lookup.
 */
public final class NonApplicationPath {

    private static volatile String prefix = "/q/";

    private NonApplicationPath() {}

    static void install(String nonApplicationRootPath) {
        prefix = normalize(nonApplicationRootPath);
    }

    /** {@code /path/} with one leading and one trailing slash; empty when the root is the application root. */
    static String normalize(String path) {
        String leading = path.startsWith("/") ? path : "/" + path;
        int end = leading.length();
        while (end > 1 && leading.charAt(end - 1) == '/') {
            end--;
        }
        String trimmed = leading.substring(0, end);
        return trimmed.equals("/") ? "" : trimmed + "/";
    }

    /** Whether a normalized request path lies under the non-application root. */
    public static boolean covers(String requestPath) {
        String current = prefix;
        return !current.isEmpty() && (requestPath + "/").startsWith(current);
    }
}

package org.quarkpress.multisite.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Site domains and paths as WordPress computes them, plus QuarkPress's storage rules. Every function runs in time
 * linear in its input; none uses regular expressions.
 */
public final class SiteAddresses {

    /** First path segments a subdirectory site of the network domain may not use (WordPress's list). */
    public static final List<String> RESERVED_FIRST_SEGMENTS = List.of(
            "page", "comments", "blog", "files", "feed", "wp-admin", "wp-content", "wp-includes", "wp-json", "embed");

    public static final int MAX_DOMAIN_LENGTH = 200;
    public static final int MAX_PATH_LENGTH = 100;

    private static final int ECHO_LENGTH = 64;
    private static final String WWW = "www.";
    private static final String ADMIN = "/wp-admin/";
    private static final String PATH_PUNCTUATION = "-._~!$&'()*+,;=:@%/";

    private SiteAddresses() {}

    /** ASCII letters lower-cased; every other character unchanged. */
    static String lowerAscii(String text) {
        char[] chars = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = (char) (c + ('a' - 'A'));
            }
        }
        return chars == null ? text : new String(chars);
    }

    /** A character a stored site path may hold: unreserved and sub-delimiter URL characters, {@code :@%/}. */
    static boolean pathCharacter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || PATH_PUNCTUATION.indexOf(c) >= 0;
    }

    /** A request host as it is compared with site domains. */
    public static String normalizeHost(String host) {
        return lowerAscii(host);
    }

    /**
     * The domains a host is looked up under: itself and, for {@code www.} hosts, the host without it. (For the bare
     * {@code www.} WordPress also looks up the empty domain, which no site has.)
     */
    public static List<String> candidateDomains(String host) {
        if (host.length() > WWW.length() && host.startsWith(WWW)) {
            return List.of(host, host.substring(WWW.length()));
        }
        return List.of(host);
    }

    /**
     * A raw request path as WordPress's multisite bootstrap prepares it for the lookup, in its order: a
     * backslash is removed and the character after it kept, as PHP's {@code stripslashes} does (so a doubled
     * backslash leaves one, and a backslash before {@code 0} leaves a NUL character); then, as WordPress does for admin requests (QuarkPress has no such flag and does it for every request),
     * everything from the last {@code /wp-admin/} on (even when it lies in the query) becomes {@code /}; then the query is cut off. The path is not decoded and not
     * lower-cased.
     */
    public static String lookupPath(String rawPath) {
        StringBuilder unescaped = null;
        for (int i = 0; i < rawPath.length(); i++) {
            char c = rawPath.charAt(i);
            if (c == '\\') {
                if (unescaped == null) {
                    unescaped = new StringBuilder(rawPath.length()).append(rawPath, 0, i);
                }
                if (i + 1 < rawPath.length()) {
                    char next = rawPath.charAt(++i);
                    unescaped.append(next == '0' ? (char) 0 : next);
                }
            } else if (unescaped != null) {
                unescaped.append(c);
            }
        }
        String plain = unescaped == null ? rawPath : unescaped.toString();
        plain = cutAdmin(plain);
        int query = plain.indexOf('?');
        return query < 0 ? plain : plain.substring(0, query);
    }

    /**
     * Every line holding {@code /wp-admin/} becomes the text before its last {@code /wp-admin/} plus {@code /}: the
     * pattern WordPress applies matches within a line, as PCRE's dot does not match a line feed. (A request path never
     * holds a raw line feed, so in practice this is one cut at the last {@code /wp-admin/}.)
     */
    private static String cutAdmin(String path) {
        if (!path.contains(ADMIN)) {
            return path;
        }
        StringBuilder cut = new StringBuilder(path.length());
        int start = 0;
        while (true) {
            int end = path.indexOf('\n', start);
            int lineEnd = end < 0 ? path.length() : end;
            int admin = lastIndexOfIn(path, ADMIN, start, lineEnd);
            if (admin >= 0) {
                cut.append(path, start, admin).append('/');
            } else {
                cut.append(path, start, lineEnd);
            }
            if (end < 0) {
                return cut.toString();
            }
            cut.append('\n');
            start = end + 1;
        }
    }

    /** The last index of {@code needle} that lies wholly in {@code [from, to)}, or -1; reads nothing outside. */
    private static int lastIndexOfIn(String text, String needle, int from, int to) {
        for (int i = to - needle.length(); i >= from; i--) {
            if (text.startsWith(needle, i)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * How many path segments a lookup compares, as WordPress computes it with the network constants defined (its usual
     * configuration, whatever the install type): all of them ({@link Integer#MAX_VALUE}) when the host is the network
     * domain and the path is the network path, ignoring ASCII case; {@code pathSegments} plus the network path's
     * segments when the host is the network domain and the path lies under a network path other than {@code /}; and
     * {@code pathSegments} otherwise. {@code pathSegments} stands for the count WordPress's
     * {@code site_by_path_segments_count} filter gives (1 without it). Host and network address are normalized; the
     * path is a {@link #lookupPath}.
     */
    public static int lookupSegments(
            String host, String lookupPath, String networkDomain, String networkPath, int pathSegments) {
        if (!host.equals(networkDomain)) {
            return pathSegments;
        }
        String path = lowerAscii(lookupPath);
        if (path.equals(networkPath)) {
            return Integer.MAX_VALUE;
        }
        if (!networkPath.equals("/") && path.startsWith(networkPath)) {
            return (int) Math.min(Integer.MAX_VALUE, (long) pathSegments + 1 + networkSlashCount(networkPath));
        }
        return pathSegments;
    }

    /** The slashes between the segments of a path trimmed of its outer slashes, empty segments included. */
    private static int networkSlashCount(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        int slashes = 0;
        for (int i = start; i < end; i++) {
            if (path.charAt(i) == '/') {
                slashes++;
            }
        }
        return slashes;
    }

    /**
     * The site paths a request path is compared with, longest first: its first {@code segments} segments (empty
     * segments and segments that are exactly {@code 0} skipped, as WordPress skips them), each shorter prefix, then
     * {@code /}. Segments are lower-cased. A candidate longer than {@value #MAX_PATH_LENGTH} characters can match no
     * stored path, so the scan stops before the segment that would make it so.
     */
    public static List<String> candidatePaths(String path, int segments) {
        List<String> parts = new ArrayList<>();
        int prefixLength = 1;
        int length = path.length();
        int i = 0;
        while (i < length && parts.size() < segments) {
            while (i < length && path.charAt(i) == '/') {
                i++;
            }
            int start = i;
            while (i < length && path.charAt(i) != '/') {
                i++;
            }
            if (i > start && !(i - start == 1 && path.charAt(start) == '0')) {
                prefixLength += i - start + 1;
                if (prefixLength > MAX_PATH_LENGTH) {
                    break;
                }
                parts.add(lowerAscii(path.substring(start, i)));
            }
        }
        List<String> paths = new ArrayList<>(parts.size() + 1);
        for (int count = parts.size(); count > 0; count--) {
            StringBuilder candidate = new StringBuilder("/");
            for (int j = 0; j < count; j++) {
                candidate.append(parts.get(j)).append('/');
            }
            paths.add(candidate.toString());
        }
        paths.add("/");
        return paths;
    }

    /**
     * A site domain as stored: WordPress keeps only letters, digits, {@code -}, {@code .} and {@code :}; QuarkPress
     * lower-cases it and refuses an empty domain, a port and more than {@value #MAX_DOMAIN_LENGTH} characters.
     */
    public static String normalizeDomain(String domain) {
        StringBuilder kept = new StringBuilder(domain.length());
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == ':') {
                kept.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                kept.append((char) (c + ('a' - 'A')));
            }
        }
        String normalized = kept.toString();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("a site domain must not be empty");
        }
        if (normalized.length() > MAX_DOMAIN_LENGTH) {
            throw new IllegalArgumentException("a site domain has at most " + MAX_DOMAIN_LENGTH + " characters");
        }
        if (normalized.indexOf(':') >= 0) {
            throw new IllegalArgumentException("a site domain has no port: " + shortened(normalized));
        }
        return normalized;
    }

    /**
     * A site path as stored: WordPress's form ({@code /}, the path without surrounding slashes, then one trailing
     * slash after trailing slashes and backslashes are removed), lower-cased; QuarkPress refuses characters a URL path
     * cannot carry unescaped, a {@code %} that does not start a {@code %XX} escape, and more than
     * {@value #MAX_PATH_LENGTH} characters.
     */
    public static String normalizePath(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        String leading = "/" + path.substring(start, end);
        int last = leading.length();
        while (last > 0 && (leading.charAt(last - 1) == '/' || leading.charAt(last - 1) == '\\')) {
            last--;
        }
        String normalized = lowerAscii(leading.substring(0, last)) + "/";
        if (normalized.length() > MAX_PATH_LENGTH) {
            throw new IllegalArgumentException("a site path has at most " + MAX_PATH_LENGTH + " characters");
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (!pathCharacter(normalized.charAt(i))) {
                throw new IllegalArgumentException("a site path holds only URL path characters (letters, digits and"
                        + " - . _ ~ ! $ & ' ( ) * + , ; = : @ % /): " + shortened(normalized));
            }
        }
        if (!percentEscapesValid(normalized)) {
            throw new IllegalArgumentException("a site path escapes characters as %XX: " + shortened(normalized));
        }
        return normalized;
    }

    private static String shortened(String value) {
        return value.length() <= ECHO_LENGTH ? value : value.substring(0, ECHO_LENGTH) + "...";
    }

    /**
     * Whether a request can reach a stored site path (normalized): false when a segment is {@code .} or {@code ..}
     * (also written {@code %2e}, in any case), when a segment is exactly {@code 0}, or when the path has an interior
     * {@code //}. Lookup resolves dot-segment requests to no site and skips empty and {@code 0} segments, so no
     * request is ever compared with such a path.
     */
    public static boolean reachablePath(String normalizedPath) {
        int i = 0;
        int length = normalizedPath.length();
        while (i < length) {
            int start = i;
            while (i < length && normalizedPath.charAt(i) != '/') {
                i++;
            }
            int dots = dotsIn(normalizedPath, start, i);
            boolean empty = i == start && start > 0 && i < length;
            boolean zero = i - start == 1 && normalizedPath.charAt(start) == '0';
            if (dots == 1 || dots == 2 || zero || empty) {
                return false;
            }
            i++;
        }
        return true;
    }

    /**
     * Whether the prepared form of a raw request path has a segment that is {@code .} or {@code ..}, also when its dots
     * are written {@code %2e}. HTTP routers remove such segments before dispatching, so the path WordPress would look
     * up is not the path that is served.
     */
    public static boolean hasDotSegment(String rawPath) {
        String path = lookupPath(rawPath);
        int i = 0;
        while (i < path.length()) {
            int start = i;
            while (i < path.length() && path.charAt(i) != '/') {
                i++;
            }
            int dots = dotsIn(path, start, i);
            if (dots == 1 || dots == 2) {
                return true;
            }
            i++;
        }
        return false;
    }

    /** How many dots the segment {@code [start, end)} consists of, or -1 when it holds anything else. */
    private static int dotsIn(String path, int start, int end) {
        int dots = 0;
        int i = start;
        while (i < end) {
            if (path.charAt(i) == '.') {
                i++;
            } else if (i + 3 <= end
                    && path.charAt(i) == '%'
                    && path.charAt(i + 1) == '2'
                    && (path.charAt(i + 2) == 'e' || path.charAt(i + 2) == 'E')) {
                i += 3;
            } else {
                return -1;
            }
            dots++;
        }
        return end > start ? dots : -1;
    }

    /** Whether every {@code %} in {@code path} is followed by two hexadecimal digits. */
    static boolean percentEscapesValid(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '%'
                    && (i + 2 >= path.length() || !hexDigit(path.charAt(i + 1)) || !hexDigit(path.charAt(i + 2)))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** How many non-empty segments a path has. */
    public static int segmentCount(String path) {
        int count = 0;
        boolean inSegment = false;
        for (int i = 0; i < path.length(); i++) {
            boolean slash = path.charAt(i) == '/';
            if (!slash && !inSegment) {
                count++;
            }
            inSegment = !slash;
        }
        return count;
    }

    /**
     * The reserved first segment a subdirectory-style site would use, if any: only sites on the network domain itself
     * (a {@code www.} network domain keeps its {@code www.}) below the network path are checked, as WordPress's Add
     * Site screen checks them only in subdirectory installs. Arguments are normalized.
     */
    public static Optional<String> reservedSegment(
            String domain, String path, String networkDomain, String networkPath) {
        if (!domain.equals(networkDomain) || !path.startsWith(networkPath) || path.equals(networkPath)) {
            return Optional.empty();
        }
        String rest = path.substring(networkPath.length());
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        return RESERVED_FIRST_SEGMENTS.contains(first) ? Optional.of(first) : Optional.empty();
    }

    /**
     * The domain, the domain without a leading {@code www.} (when it has one), and {@code www.} plus the domain: the
     * forms a request for the domain may take, since every lookup also tries a {@code www.} host without its prefix.
     */
    public static List<String> wwwForms(String domain) {
        List<String> forms = new ArrayList<>(3);
        forms.add(domain);
        if (domain.length() > WWW.length() && domain.startsWith(WWW)) {
            forms.add(domain.substring(WWW.length()));
        }
        forms.add(WWW + domain);
        return List.copyOf(forms);
    }

    /** Where a normalized host lies relative to the network domain. */
    public static HostClass classify(String host, String networkDomain) {
        String base = networkDomain.length() > WWW.length() && networkDomain.startsWith(WWW)
                ? networkDomain.substring(WWW.length())
                : networkDomain;
        if (host.equals(networkDomain) || host.equals(base) || host.equals(WWW + base)) {
            return HostClass.NETWORK;
        }
        if (host.length() > base.length() + 1
                && host.endsWith(base)
                && host.charAt(host.length() - base.length() - 1) == '.') {
            return HostClass.SUBDOMAIN;
        }
        return HostClass.FOREIGN;
    }
}

package org.quarkpress.restwp.runtime;

import java.util.List;
import org.quarkpress.lib.wpkses.Kses;

/**
 * WordPress's sanitizing of text and URLs from requests, in work linear in the input: text-field and textarea-field
 * sanitizing, and URL sanitizing for storage. A removal WordPress repeats until nothing is left to remove is done in
 * one pass with a stack, which reaches the same result because the removed sequences cannot overlap.
 */
public final class TextFields {

    /** WordPress's allowed URL protocols. */
    static final List<String> PROTOCOLS = List.of(
            "http", "https", "ftp", "ftps", "mailto", "news", "irc", "irc6", "ircs", "gopher", "nntp", "feed", "telnet",
            "mms", "rtsp", "sms", "svn", "tel", "fax", "xmpp", "webcal", "urn");

    private static final Kses KSES = Kses.wordPress();

    private TextFields() {}

    /**
     * WordPress's text-field sanitizing: text with a lone surrogate (invalid UTF-8) becomes empty; when it holds a
     * '<', a lone '<' is escaped, tags are stripped (script and style elements with their content) and a '<' before a
     * line feed is escaped; runs of spaces, tabs and line breaks become one space; the ends are trimmed (NUL included);
     * percent-encoded octets are removed until none is left, and when any was, runs of spaces become one and the ends
     * are trimmed again.
     */
    public static String textField(String text) {
        return field(text, false);
    }

    /** {@link #textField} keeping tabs and line breaks (WordPress's textarea-field sanitizing). */
    public static String textareaField(String text) {
        return field(text, true);
    }

    private static String field(String text, boolean keepNewlines) {
        if (hasLoneSurrogate(text)) {
            return "";
        }
        String filtered = text;
        if (filtered.indexOf('<') >= 0) {
            filtered = Kses.lessThan(filtered);
            filtered = Kses.stripAllTags(filtered, false);
            filtered = filtered.replace("<\n", "&lt;\n");
        }
        if (!keepNewlines) {
            filtered = collapse(filtered, true);
        }
        filtered = trim(filtered);
        String removed = removeOctets(filtered, new long[1]);
        return removed.length() == filtered.length() ? filtered : trim(collapse(removed, false));
    }

    /**
     * The text with every percent-encoded octet ({@code %} and two hexadecimal digits) removed, again and again until
     * none is left ({@code %%4141} gives the empty string). Adds the characters looked at to {@code work[0]}.
     */
    static String removeOctets(String text, long[] work) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            work[0]++;
            out.append(text.charAt(i));
            int n = out.length();
            if (n >= 3 && out.charAt(n - 3) == '%' && hex(out.charAt(n - 2)) && hex(out.charAt(n - 1))) {
                out.setLength(n - 3);
            }
        }
        return out.toString();
    }

    /**
     * WordPress's URL sanitizing for storage: leading whitespace trimmed, spaces encoded as {@code %20}, characters
     * outside the URL set removed (non-ASCII kept); escaped line breaks ({@code %0d}, {@code %0a}, {@code %0D},
     * {@code %0A}) removed until none is left, except in a {@code mailto:} URL; {@code ;//} becomes {@code ://};
     * {@code http://} is prefixed to a URL without a colon that does not start with '/', '#' or '?' and is not a
     * {@code name.php} path; square brackets after the scheme and authority are percent-encoded; and a URL not starting
     * with '/' must keep its scheme through WordPress's protocol filter, which lower-cases it, or becomes empty.
     */
    public static String url(String text) {
        if (text.isEmpty()) {
            return "";
        }
        String url = ltrim(text).replace(" ", "%20");
        StringBuilder kept = new StringBuilder(url.length());
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (urlChar(c)) {
                kept.append(c);
            }
        }
        url = kept.toString();
        if (url.isEmpty()) {
            return "";
        }
        if (!url.regionMatches(true, 0, "mailto:", 0, 7)) {
            url = removeLineBreakEscapes(url, new long[1]);
        }
        url = url.replace(";//", "://");
        // removing escaped line breaks can leave nothing, which then gets the prefix alone
        if (url.indexOf(':') < 0 && (url.isEmpty() || "/#?".indexOf(url.charAt(0)) < 0) && !phpScript(url)) {
            url = "http://" + url;
        }
        if (url.indexOf('[') >= 0 || url.indexOf(']') >= 0) {
            url = encodeBrackets(url);
        }
        if (url.charAt(0) == '/') {
            return url;
        }
        String good = KSES.badProtocol(url, PROTOCOLS);
        return asciiLower(good).equals(asciiLower(url)) ? good : "";
    }

    /**
     * The URL with {@code %0d}, {@code %0a}, {@code %0D} and {@code %0A} removed until none is left. Adds the
     * characters looked at to {@code work[0]}.
     */
    static String removeLineBreakEscapes(String text, long[] work) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            work[0]++;
            out.append(text.charAt(i));
            int n = out.length();
            if (n >= 3 && out.charAt(n - 3) == '%' && out.charAt(n - 2) == '0') {
                char last = out.charAt(n - 1);
                if (last == 'd' || last == 'a' || last == 'D' || last == 'A') {
                    out.setLength(n - 3);
                }
            }
        }
        return out.toString();
    }

    /**
     * Square brackets percent-encoded after the URL's front, as WordPress does it: the front is the scheme with
     * {@code ://} (or {@code //} for a URL starting with '/'), then the user, password, host and port as PHP's URL
     * parser reads them (nothing of them when it refuses the URL); every occurrence of the front is removed to give the
     * rest, and every occurrence of the rest in the URL is replaced by its encoded form (so a rest that no longer
     * occurs leaves the URL as it was).
     */
    private static String encodeBrackets(String url) {
        String front = front(url);
        String rest = front.isEmpty() ? url : replaceEach(url, front, "");
        if (rest.isEmpty()) {
            return url;
        }
        return replaceEach(url, rest, rest.replace("[", "%5B").replace("]", "%5D"));
    }

    private static String front(String url) {
        String fallback = url.charAt(0) == '/' ? "//" : "";
        int colon = url.indexOf(':');
        boolean scheme = colon > 0 && scheme(url, colon);
        int start;
        String prefix;
        if (scheme && url.startsWith("//", colon + 1)) {
            start = colon + 3;
            prefix = url.substring(0, colon) + "://";
        } else if (url.startsWith("//")) {
            start = 2;
            prefix = "//";
        } else if (scheme && !portOnly(url, colon + 1)) {
            // PHP reads "mailto:x" as a scheme and a path: the front is the scheme with "://", found or not
            return url.substring(0, colon) + "://";
        } else {
            return fallback;
        }
        int end = start;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String authority = url.substring(start, end);
        StringBuilder front = new StringBuilder(prefix);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            front.append(authority, 0, at).append('@');
        }
        String hostPort = authority.substring(at + 1);
        // PHP reads a port after the last colon, unless the host is bracketed whole ("[::1]")
        boolean bracketed = hostPort.startsWith("[") && hostPort.endsWith("]");
        int portColon = bracketed ? -1 : hostPort.lastIndexOf(':');
        String host = portColon < 0 ? hostPort : hostPort.substring(0, portColon);
        String port = portColon < 0 ? "" : hostPort.substring(portColon + 1);
        if (host.isEmpty()) {
            return fallback;
        }
        front.append(host);
        if (!port.isEmpty()) {
            int sign = port.charAt(0) == '+' || port.charAt(0) == '-' ? 1 : 0;
            if (port.length() > 5 || port.length() == sign) {
                return fallback;
            }
            int number = 0;
            for (int i = sign; i < port.length(); i++) {
                char c = port.charAt(i);
                if (c < '0' || c > '9') {
                    return fallback;
                }
                number = number * 10 + (c - '0');
            }
            if (number > 65535 || (port.charAt(0) == '-' && number != 0)) {
                return fallback;
            }
            front.append(':').append(number);
        }
        return front.toString();
    }

    /** Whether the text from {@code from} up to a '/' or the end is digits only, which PHP reads as a port. */
    private static boolean portOnly(String url, int from) {
        int i = from;
        while (i < url.length() && url.charAt(i) >= '0' && url.charAt(i) <= '9') {
            i++;
        }
        return i > from && (i == url.length() || url.charAt(i) == '/');
    }

    /**
     * Every non-overlapping occurrence of {@code target} (not empty), leftmost first, replaced, as PHP's
     * {@code str_replace} does; the search is linear (Knuth-Morris-Pratt).
     */
    static String replaceEach(String text, String target, String replacement) {
        int m = target.length();
        int[] fallback = new int[m];
        for (int i = 1, k = 0; i < m; i++) {
            while (k > 0 && target.charAt(i) != target.charAt(k)) {
                k = fallback[k - 1];
            }
            if (target.charAt(i) == target.charAt(k)) {
                k++;
            }
            fallback[i] = k;
        }
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (int i = 0, k = 0; i < text.length(); i++) {
            while (k > 0 && text.charAt(i) != target.charAt(k)) {
                k = fallback[k - 1];
            }
            if (text.charAt(i) == target.charAt(k)) {
                k++;
            }
            if (k == m) {
                int startOfMatch = i - m + 1;
                out.append(text, copied, startOfMatch).append(replacement);
                copied = i + 1;
                k = 0;
            }
        }
        return out.append(text, copied, text.length()).toString();
    }

    private static boolean scheme(String url, int end) {
        for (int i = 0; i < end; i++) {
            char c = url.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!(letter || (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')))) {
                return false;
            }
        }
        return true;
    }

    /** One or more letters, digits or '-' followed by {@code .php}, at the start. */
    private static boolean phpScript(String url) {
        int i = 0;
        while (i < url.length()) {
            char c = url.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-')) {
                break;
            }
            i++;
        }
        return i > 0 && url.regionMatches(true, i, ".php", 0, 4);
    }

    private static boolean urlChar(char c) {
        if (c >= 0x80) {
            return true;
        }
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || "-~+_.?#=!&;,/:%@$|*'()[]".indexOf(c) >= 0;
    }

    private static String asciiLower(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return out.toString();
    }

    /** Runs of whitespace as one space: spaces, tabs and line breaks ({@code all}), or only spaces. */
    private static String collapse(String text, boolean all) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inRun = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean space = c == ' ' || (all && (c == '\t' || c == '\n' || c == '\r'));
            if (space) {
                if (!inRun) {
                    out.append(' ');
                }
                inRun = true;
            } else {
                out.append(c);
                inRun = false;
            }
        }
        return out.toString();
    }

    /** PHP's {@code trim}: spaces, tabs, line breaks, vertical tabs and NUL at both ends. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && trimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && trimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static String ltrim(String text) {
        int start = 0;
        while (start < text.length() && trimmed(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    private static boolean trimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == 0;
    }

    private static boolean hex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }
}

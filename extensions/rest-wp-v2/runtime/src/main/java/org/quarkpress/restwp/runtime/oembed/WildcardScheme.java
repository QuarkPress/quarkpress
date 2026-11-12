package org.quarkpress.restwp.runtime.oembed;

/**
 * WordPress's wildcard provider schemes: {@code *} stands for one or more characters, everything else is literal and
 * compared ignoring ASCII case, a scheme starting with {@code http://} also matches {@code https://}, and the scheme is
 * not anchored (a URL that holds the scheme anywhere matches). The match is a greedy walk that backs up to the last
 * star only, so it is linear in practice and never a backtracking regular expression.
 */
public final class WildcardScheme {

    private WildcardScheme() {}

    /** Whether {@code url} holds a match of {@code scheme}; an empty scheme matches nothing. */
    public static boolean matches(String scheme, String url) {
        if (scheme == null || url == null || scheme.isEmpty()) {
            return false;
        }
        if (startsWithIgnoreCase(scheme, "http://")) {
            // a leading http:// stands for http:// or https://
            String rest = scheme.substring("http://".length());
            return walk("http://" + rest, url) || walk("https://" + rest, url);
        }
        return walk(scheme, url);
    }

    /**
     * The glob walk, with an implicit star (of zero or more characters) before the scheme and after it: {@code s}
     * runs over the URL, {@code p} over the scheme, and a mismatch backs up to the last star, which then takes one
     * more character. A star of the scheme takes one character first and then behaves as a star of zero or more.
     */
    private static boolean walk(String scheme, String url) {
        int n = url.length();
        int m = scheme.length();
        int s = 0;
        int p = 0;
        // the position after the last star seen and the URL position it matched up to: the implicit leading star
        int starP = 0;
        int starS = 0;
        boolean starSeen = true;
        while (s < n) {
            if (p == m) {
                // the whole scheme matched: the implicit trailing star takes the rest
                return true;
            }
            char pc = scheme.charAt(p);
            if (pc == '*') {
                // one character at least, then zero or more
                starP = p + 1;
                starS = s + 1;
                starSeen = true;
                p++;
                s++;
            } else if (same(pc, url.charAt(s))) {
                p++;
                s++;
            } else if (starSeen) {
                // back up: the last star takes one more character
                starS++;
                p = starP;
                s = starS;
            } else {
                return false;
            }
        }
        return p == m;
    }

    private static boolean same(char a, char b) {
        return lower(a) == lower(b);
    }

    private static char lower(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
    }

    private static boolean startsWithIgnoreCase(String text, String prefix) {
        if (text.length() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (!same(text.charAt(i), prefix.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

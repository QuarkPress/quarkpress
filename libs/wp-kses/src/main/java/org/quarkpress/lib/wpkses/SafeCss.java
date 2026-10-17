package org.quarkpress.lib.wpkses;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Filters inline CSS like safecss_filter_attr, with linear-time scanners instead of regular expressions. */
final class SafeCss {

    /** Properties whose values get url() handling (not configurable in WordPress). */
    static final Set<String> URL_PROPERTIES = Set.of(
            "background",
            "background-image",
            "cursor",
            "filter",
            "list-style",
            "list-style-image",
            "clip-path",
            "fill",
            "marker",
            "marker-end",
            "marker-mid",
            "marker-start",
            "mask",
            "stroke");

    /** Properties whose values get gradient handling. */
    static final Set<String> GRADIENT_PROPERTIES = Set.of("background", "background-image");

    /** Functions removed with their balanced arguments before the final test. */
    static final Set<String> STRIPPED_FUNCTIONS = Set.of(
            "var",
            "calc",
            "min",
            "max",
            "minmax",
            "clamp",
            "repeat",
            "matrix",
            "matrix3d",
            "perspective",
            "rotate",
            "rotate3d",
            "rotateX",
            "rotateY",
            "rotateZ",
            "scale",
            "scale3d",
            "scaleX",
            "scaleY",
            "scaleZ",
            "skew",
            "skewX",
            "skewY",
            "translate",
            "translate3d",
            "translateX",
            "translateY",
            "translateZ",
            "circle",
            "ellipse",
            "inset",
            "path",
            "polygon",
            "rect",
            "shape",
            "xywh");

    private static final List<String> GRADIENTS = List.of("linear-gradient(", "radial-gradient(", "conic-gradient(");

    /**
     * Above this many url() or gradient texts in one declaration, the matched spans are removed instead
     * of every occurrence of each text, which keeps the work linear (see the README differences).
     */
    static final int EXACT_REMOVAL_LIMIT = 32;

    private final List<String> properties;
    private final Set<String> propertySet;
    private final boolean customProperties;
    private final CssAllowFilter allowFilter;
    private final List<String> protocols;

    /**
     * @param properties the allowed properties (WordPress's filtered safe_style_css list); empty disables filtering
     * @param allowFilter may be null
     * @param protocols the protocols url() values may use (always the instance's default list)
     */
    SafeCss(List<String> properties, CssAllowFilter allowFilter, List<String> protocols) {
        this.properties = List.copyOf(properties);
        this.propertySet = new HashSet<>(this.properties);
        this.customProperties = propertySet.contains("--*");
        this.allowFilter = allowFilter;
        this.protocols = List.copyOf(protocols);
    }

    List<String> properties() {
        return properties;
    }

    String filter(String css) {
        String cleaned = removeLineBreaksAndTabs(NullFilter.clean(css, false));
        if (properties.isEmpty()) {
            return cleaned;
        }
        String trimmed = TextUtils.phpTrim(cleaned);
        StringBuilder out = new StringBuilder(trimmed.length());
        int start = 0;
        while (true) {
            int semicolon = trimmed.indexOf(';', start);
            String piece = semicolon < 0 ? trimmed.substring(start) : trimmed.substring(start, semicolon);
            if (!piece.isEmpty()) {
                String declaration = TextUtils.phpTrim(piece);
                if (accepts(declaration)) {
                    if (!out.isEmpty()) {
                        out.append(';');
                    }
                    out.append(declaration);
                }
            }
            if (semicolon < 0) {
                return out.toString();
            }
            start = semicolon + 1;
        }
    }

    private static String removeLineBreaksAndTabs(String s) {
        if (s.indexOf('\n') < 0 && s.indexOf('\r') < 0 && s.indexOf('\t') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\n' && c != '\r' && c != '\t') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private boolean accepts(String declaration) {
        String test = declaration;
        int colon = declaration.indexOf(':');
        if (colon >= 0) {
            String property = TextUtils.phpTrim(declaration.substring(0, colon));
            String value = declaration.substring(colon + 1);
            boolean custom = customProperties && isCustomPropertyName(property);
            if (!custom && !propertySet.contains(property)) {
                return false;
            }
            boolean urls;
            boolean gradients;
            if (custom) {
                String trimmedValue = TextUtils.phpTrim(value);
                urls = trimmedValue.startsWith("url(");
                gradients = trimmedValue.contains("-gradient(");
            } else {
                urls = URL_PROPERTIES.contains(property);
                gradients = GRADIENT_PROPERTIES.contains(property);
            }
            if (urls) {
                test = removeUrls(test, colon + 1);
                if (test == null) {
                    return false;
                }
            }
            if (gradients) {
                test = removeGradients(test);
                if (test == null) {
                    return false;
                }
            }
        }
        test = stripFunctions(test);
        boolean allowed = !hasForbiddenText(test);
        return allowFilter == null ? allowed : allowFilter.allow(allowed, test);
    }

    /** "--" followed by one or more ASCII letters, digits, '-' or '_'. */
    static boolean isCustomPropertyName(String property) {
        if (!property.startsWith("--") || property.length() == 2) {
            return false;
        }
        for (int i = 2; i < property.length(); i++) {
            char c = property.charAt(i);
            if (!TextUtils.isAsciiAlnum(c) && c != '-' && c != '_') {
                return false;
            }
        }
        return true;
    }

    /**
     * Every "url(" + non-')' characters + ")" in the value (which starts at {@code valueStart} of the
     * declaration) must hold a safe URL; null when one does not, else the test string without those texts.
     */
    private String removeUrls(String test, int valueStart) {
        List<int[]> spans = new ArrayList<>();
        int i = test.indexOf("url(", valueStart);
        while (i >= 0) {
            int close = test.indexOf(')', i + 4);
            if (close < 0) {
                break;
            }
            if (close == i + 4) {
                i = test.indexOf("url(", i + 1);
                continue;
            }
            if (!isSafeUrl(test.substring(i + 4, close))) {
                return null;
            }
            spans.add(new int[] {i, close + 1});
            i = test.indexOf("url(", close + 1);
        }
        return removeMatches(test, spans, "url(");
    }

    private boolean isSafeUrl(String body) {
        String inner = urlInner(body);
        if (TextUtils.phpEmpty(inner)) {
            return false;
        }
        String url = TextUtils.phpTrim(inner);
        return !TextUtils.phpEmpty(url) && ProtocolFilter.filter(url, protocols).equals(url);
    }

    /**
     * The URL inside "url(" body ")": leading spaces skipped; quotes removed when the body (without trailing
     * spaces) starts and ends with the same quote; otherwise everything after the leading spaces.
     */
    static String urlInner(String body) {
        int start = 0;
        while (start < body.length() && TextUtils.isPcreSpace(body.charAt(start))) {
            start++;
        }
        int end = body.length();
        while (end > start && TextUtils.isPcreSpace(body.charAt(end - 1))) {
            end--;
        }
        if (end - start >= 2) {
            char quote = body.charAt(start);
            if ((quote == '"' || quote == '\'') && body.charAt(end - 1) == quote) {
                return body.substring(start + 1, end - 1);
            }
        }
        return body.substring(start);
    }

    /** Removes gradient functions whose arguments nest at most one level of parentheses; null if the span fallback leaves one behind. */
    static String removeGradients(String test) {
        List<int[]> spans = new ArrayList<>();
        int position = 0;
        while (position < test.length()) {
            int[] match = nextGradient(test, position);
            if (match == null) {
                break;
            }
            spans.add(match);
            position = match[1];
        }
        return removeMatches(test, spans, "-gradient(");
    }

    /** The leftmost gradient match starting at or after {@code from}, as {start, end}, or null. */
    private static int[] nextGradient(String s, int from) {
        int dash = s.indexOf("-gradient(", from);
        while (dash >= 0) {
            for (String kind : GRADIENTS) {
                int kindStart = dash + "-gradient(".length() - kind.length();
                if (kindStart >= from && s.startsWith(kind, kindStart)) {
                    int end = gradientArgumentsEnd(s, dash + "-gradient".length());
                    if (end >= 0) {
                        int prefixed = kindStart - "repeating-".length();
                        boolean repeating = prefixed >= from && s.startsWith("repeating-", prefixed);
                        return new int[] {repeating ? prefixed : kindStart, end};
                    }
                    break;
                }
            }
            dash = s.indexOf("-gradient(", dash + 1);
        }
        return null;
    }

    /** End (exclusive) of "(" + (non-parentheses | "(" non-parentheses ")")* + ")" at {@code open}, or -1. */
    private static int gradientArgumentsEnd(String s, int open) {
        int i = open + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ')') {
                return i + 1;
            }
            if (c == '(') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != '(' && s.charAt(j) != ')') {
                    j++;
                }
                if (j >= s.length() || s.charAt(j) == '(') {
                    return -1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Removes allowed functions with their balanced arguments, at word starts, case-sensitively. */
    static String stripFunctions(String test) {
        int n = test.length();
        if (test.indexOf('(') < 0) {
            return test;
        }
        int[] closing = matchingParentheses(test);
        StringBuilder out = new StringBuilder(n);
        int copied = 0;
        int i = 0;
        while (i < n) {
            char c = test.charAt(i);
            boolean wordStart = TextUtils.isAsciiLetter(c) && (i == 0 || !TextUtils.isWordChar(test.charAt(i - 1)));
            if (!wordStart) {
                i++;
                continue;
            }
            int end = i;
            while (end < n && TextUtils.isAsciiAlnum(test.charAt(end))) {
                end++;
            }
            if (end < n
                    && test.charAt(end) == '('
                    && closing[end] >= 0
                    && STRIPPED_FUNCTIONS.contains(test.substring(i, end))) {
                out.append(test, copied, i);
                copied = closing[end] + 1;
                i = copied;
            } else {
                i = end;
            }
        }
        return out.append(test, copied, n).toString();
    }

    /** For each '(' the index of its matching ')', or -1; other positions are -1. */
    private static int[] matchingParentheses(String s) {
        int[] closing = new int[s.length()];
        Arrays.fill(closing, -1);
        Deque<Integer> open = new ArrayDeque<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                open.push(i);
            } else if (c == ')' && !open.isEmpty()) {
                closing[open.pop()] = i;
            }
        }
        return closing;
    }

    /** Backslash, '(', '&', '=', '}' or "/*" make the declaration unsafe. */
    static boolean hasForbiddenText(String test) {
        for (int i = 0; i < test.length(); i++) {
            char c = test.charAt(i);
            if (c == '\\' || c == '(' || c == '&' || c == '=' || c == '}') {
                return true;
            }
            if (c == '/' && i + 1 < test.length() && test.charAt(i + 1) == '*') {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes matched texts as WordPress does: every match, in match order and duplicates included, replaces all
     * current occurrences of its text (PHP's str_replace), so a later match can remove text that an earlier
     * removal joined together. To stay linear, once more than {@link #EXACT_REMOVAL_LIMIT} replacements have
     * removed something (or the number of searches grows past a few times that), only the matched spans are
     * removed instead, and the result is rejected (null) unless that is provably the same: every {@code marker}
     * must start a matched span and none may be left in what remains.
     */
    static String removeMatches(String subject, List<int[]> spans, String marker) {
        if (spans.isEmpty()) {
            return subject;
        }
        String result = subject;
        Map<String, Integer> checkedAt = new HashMap<>();
        int version = 0;
        int searches = 0;
        boolean exact = true;
        for (int[] span : spans) {
            String text = subject.substring(span[0], span[1]);
            Integer seen = checkedAt.get(text);
            if (seen != null && seen == version) {
                continue;
            }
            checkedAt.put(text, version);
            if (++searches > 4 * EXACT_REMOVAL_LIMIT || version > EXACT_REMOVAL_LIMIT) {
                exact = false;
                break;
            }
            if (result.indexOf(text) >= 0) {
                result = result.replace(text, "");
                version++;
            }
        }
        if (exact) {
            return result;
        }
        // Span removal equals WordPress's result only when every marker starts a matched span (so no removed
        // text occurs anywhere else) and no gradient text is a suffix of a longer "repeating-" match.
        int markers = 0;
        for (int at = subject.indexOf(marker); at >= 0; at = subject.indexOf(marker, at + 1)) {
            markers++;
        }
        if (markers != spans.size()) {
            return null;
        }
        StringBuilder out = new StringBuilder(subject.length());
        int copied = 0;
        for (int[] span : spans) {
            if (subject.startsWith("repeating-", span[0])) {
                return null;
            }
            out.append(subject, copied, span[0]);
            copied = span[1];
        }
        String residue = out.append(subject, copied, subject.length()).toString();
        return residue.contains(marker) ? null : residue;
    }
}

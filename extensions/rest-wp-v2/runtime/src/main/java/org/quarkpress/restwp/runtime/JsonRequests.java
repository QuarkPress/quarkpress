package org.quarkpress.restwp.runtime;

import java.util.Optional;

/**
 * WordPress's test of whether a request wants or sends JSON, which decides how a request refused before the REST server
 * runs is answered (a JSON error object or an HTML page).
 *
 * <p>A header value names JSON when it holds, ignoring ASCII case, {@code application/} preceded by the start of the
 * value, a comma or ASCII white space, then either {@code json} or a subtype of at least one character from the
 * letters, digits and {@code _ ! # $ & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^} followed by {@code +json}, then
 * optionally {@code +oembed}, then the end of the value, a comma, a semicolon or ASCII white space. One pass over the
 * value; no regular expression.
 */
public final class JsonRequests {

    private static final String APPLICATION = "application/";

    private JsonRequests() {}

    /** Whether the {@code Accept} or the {@code Content-Type} header names a JSON media type. */
    public static boolean isJson(Optional<String> accept, Optional<String> contentType) {
        return accept.map(JsonRequests::namesJson).orElse(false)
                || contentType.map(JsonRequests::namesJson).orElse(false);
    }

    /** Whether one header value names a JSON media type, as described on the class. */
    static boolean namesJson(String value) {
        return scan(value, new long[1]);
    }

    /**
     * The loop steps {@link #namesJson} takes for {@code value}; each step does a bounded amount of work (at most one
     * comparison of a constant of up to twelve characters), so the count measures the whole cost.
     */
    static long steps(String value) {
        long[] steps = new long[1];
        scan(value, steps);
        return steps[0];
    }

    private static boolean scan(String value, long[] steps) {
        int length = value.length();
        // jsonAt[j]: "json" starts at j and is followed by an optional "+oembed" and a terminator
        boolean[] jsonAt = new boolean[length + 1];
        for (int j = 0; j + 4 <= length; j++) {
            steps[0]++;
            jsonAt[j] = jsonWithTail(value, j);
        }
        // nextPlusJson[i]: the least j >= i with '+' at j - 1 and jsonAt[j], or length + 1 when there is none
        int[] nextPlusJson = new int[length + 2];
        nextPlusJson[length + 1] = length + 1;
        for (int j = length; j >= 0; j--) {
            steps[0]++;
            nextPlusJson[j] = j >= 1 && value.charAt(j - 1) == '+' && jsonAt[j] ? j : nextPlusJson[j + 1];
        }
        // runEnd[i]: the first index >= i whose character is not a subtype character
        int[] runEnd = new int[length + 1];
        runEnd[length] = length;
        for (int i = length - 1; i >= 0; i--) {
            steps[0]++;
            runEnd[i] = subtypeChar(value.charAt(i)) ? runEnd[i + 1] : i;
        }
        for (int a = 0; a + APPLICATION.length() <= length; a++) {
            steps[0]++;
            if ((a == 0 || separator(value.charAt(a - 1))) && regionIgnoringCase(value, a, APPLICATION)) {
                int p = a + APPLICATION.length();
                if (jsonAt[p]) {
                    return true;
                }
                // a subtype of at least one character, its last one the '+' before "json"
                int j = nextPlusJson[Math.min(p + 2, length + 1)];
                if (j <= runEnd[p]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jsonWithTail(String value, int j) {
        if (!regionIgnoringCase(value, j, "json")) {
            return false;
        }
        int after = j + 4;
        if (terminator(value, after)) {
            return true;
        }
        return regionIgnoringCase(value, after, "+oembed") && terminator(value, after + 7);
    }

    private static boolean terminator(String value, int at) {
        if (at == value.length()) {
            return true;
        }
        char c = value.charAt(at);
        return c == ';' || c == ',' || space(c);
    }

    private static boolean separator(char c) {
        return c == ',' || space(c);
    }

    /** ASCII white space as PCRE's {@code \s} matches it: space, tab, line feed, vertical tab, form feed, return. */
    private static boolean space(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /** Letters, digits and {@code _ ! # $ .}, plus every character from {@code &} to {@code ^}. */
    private static boolean subtypeChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '&' && c <= '^') || c == '_' || c == '!' || c == '#' || c == '$';
    }

    private static boolean regionIgnoringCase(String value, int at, String lowerCase) {
        if (at + lowerCase.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < lowerCase.length(); i++) {
            char c = value.charAt(at + i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            if (c != lowerCase.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}

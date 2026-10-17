package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.List;

/**
 * HTML as WordPress's shortcode code sees it: where an "element" that starts at a {@code <} ends, and how a
 * normal element splits into front matter, attribute tokens and back matter. There is no check that a tag name
 * follows the {@code <}: a bare less-than sign in prose opens an element too.
 */
final class HtmlElement {

    /** A split element: {@code front + String.join("", attributes) + back} is the element text. */
    record Parsed(String front, String name, List<String> attributes, String back) {}

    private HtmlElement() {}

    /** The end (exclusive) of the element that starts with the {@code <} at {@code lt}. */
    static int end(String s, int lt) {
        if (s.startsWith("<!--", lt)) {
            int k = s.indexOf("-->", lt + 2);
            return k < 0 ? s.length() : k + 3;
        }
        if (s.startsWith("<![CDATA[", lt)) {
            int k = s.indexOf("]]>", lt + 9);
            return k < 0 ? s.length() : k + 3;
        }
        int k = s.indexOf('>', lt + 1);
        return k < 0 ? s.length() : k + 1;
    }

    /** Splits a normal element; null for a closing tag or attribute text that does not split into tokens. */
    static Parsed parse(String element) {
        int n = element.length();
        int i = Chars.skipSpaces(element, 1, n);
        int nameStart = i;
        while (i < n && Chars.isAsciiAlphanumeric(element.charAt(i))) {
            i++;
        }
        if (i == nameStart) {
            return null; // also "</p>": the slash is not alphanumeric
        }
        String name = element.substring(nameStart, i);
        i = Chars.skipSpaces(element, i, n);
        String front = element.substring(0, i);
        int gt = element.indexOf('>', i);
        if (gt >= 0 && gt != n - 1) {
            return null;
        }
        int attributesEnd = gt < 0 ? n : gt;
        String attributes = element.substring(i, attributesEnd);
        int slashStart = attributes.length();
        int trimmed = Chars.trimEnd(attributes, 0, attributes.length());
        if (trimmed > 0 && attributes.charAt(trimmed - 1) == '/') {
            slashStart = Chars.trimEnd(attributes, 0, trimmed - 1);
        }
        List<String> tokens = splitAttributes(attributes.substring(0, slashStart));
        if (tokens == null) {
            return null;
        }
        return new Parsed(front, name, tokens, attributes.substring(slashStart) + (gt < 0 ? "" : ">"));
    }

    /** Splits attribute text into tokens, each with its trailing spaces; null when the text does not split. */
    static List<String> splitAttributes(String a) {
        List<String> tokens = new ArrayList<>();
        int n = a.length();
        int i = 0;
        while (i < n) {
            int start = i;
            int afterName = readName(a, i);
            if (afterName < 0) {
                return null;
            }
            int afterValue = readValue(a, afterName);
            if (afterValue >= 0) {
                i = afterValue;
            } else if (afterName == n) {
                i = n;
            } else if (Chars.isSpace(a.charAt(afterName))) {
                i = afterName + 1;
            } else {
                return null;
            }
            i = Chars.skipSpaces(a, i, n);
            tokens.add(a.substring(start, i));
        }
        return tokens;
    }

    /** True for elements like {@code <[email]>}: {@code <}, spaces, then a bracketed name. */
    static boolean startsWithBracketedName(String element) {
        int i = Chars.skipSpaces(element, 1, element.length());
        return i < element.length() && element.charAt(i) == '[' && bracketedName(element, i) >= 0;
    }

    /** At a {@code [}: {@code [} or {@code [[}, chars other than brackets, {@code ]}, one optional extra {@code ]}. */
    static int bracketedName(String s, int i) {
        int n = s.length();
        int j = i + 1;
        if (j < n && s.charAt(j) == '[') {
            j++;
        }
        int k = j;
        while (k < n && s.charAt(k) != '[' && s.charAt(k) != ']') {
            k++;
        }
        if (k == j || k >= n || s.charAt(k) != ']') {
            return -1;
        }
        k++;
        if (k < n && s.charAt(k) == ']') {
            k++;
        }
        return k;
    }

    /** An attribute name or a bracketed name starting at i; its end, or -1. */
    private static int readName(String a, int i) {
        char c = a.charAt(i);
        if (Chars.isAsciiLetter(c) || c == '_') {
            int j = i + 1;
            while (j < a.length() && isNameChar(a.charAt(j))) {
                j++;
            }
            return j;
        }
        return c == '[' ? bracketedName(a, i) : -1;
    }

    private static boolean isNameChar(char c) {
        return Chars.isAsciiAlphanumeric(c) || c == '-' || c == '_' || c == ':' || c == '.';
    }

    /** Spaces, {@code =}, spaces and a value after a name; the end, or -1. */
    private static int readValue(String a, int from) {
        int n = a.length();
        int eq = Chars.skipSpaces(a, from, n);
        if (eq >= n || a.charAt(eq) != '=') {
            return -1;
        }
        int v = Chars.skipSpaces(a, eq + 1, n);
        if (v >= n) {
            return -1;
        }
        char quote = a.charAt(v);
        if (quote == '"' || quote == '\'') {
            int close = a.indexOf(quote, v + 1);
            return close < 0 ? -1 : close + 1;
        }
        int k = v;
        while (k < n && !Chars.isSpace(a.charAt(k)) && a.charAt(k) != '"' && a.charAt(k) != '\'') {
            k++;
        }
        if (k == n) {
            return n;
        }
        return Chars.isSpace(a.charAt(k)) ? k + 1 : -1;
    }
}

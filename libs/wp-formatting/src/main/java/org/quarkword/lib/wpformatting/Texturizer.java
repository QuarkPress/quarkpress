package org.quarkword.lib.wpformatting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;

/**
 * Walks the segments of a text: comments stay as they are; every other tag gets the ampersand fix and may open or
 * close a no-texturize zone; shortcode tags may open or close a no-texturize shortcode zone; text is texturized
 * only outside both kinds of zone. A zone is a stack of names, so nested zones need as many closers.
 */
final class Texturizer {

    private static final String ELLIPSIS = "&#8230;";
    private static final String TRADEMARK = " &#8482;";
    private static final String AMPERSAND = "&#038;";

    private Texturizer() {}

    static String apply(
            String text, TexturizeStrings strings, Set<String> tags, Set<String> shortcodes, Set<String> registered) {
        Set<String> used = text.indexOf('[') >= 0 ? TexturizeSplitter.usedShortcodes(text, registered) : Set.of();
        StringBuilder out = new StringBuilder(text.length() + (text.length() >> 3) + 16);
        Deque<String> tagStack = new ArrayDeque<>();
        Deque<String> shortcodeStack = new ArrayDeque<>();
        TexturizeSplitter.forEachSegment(text, used, (start, end, kind) -> {
            switch (kind) {
                case COMMENT -> out.append(text, start, end);
                case ELEMENT -> {
                    String element = fixAmpersands(text.substring(start, end));
                    pushOrPop(tagStack, element, tags);
                    out.append(element);
                }
                case SHORTCODE -> {
                    String shortcode = text.substring(start, end);
                    if (!shortcode.startsWith("[[") && !shortcode.endsWith("]]")) {
                        pushOrPop(shortcodeStack, shortcode, shortcodes);
                    }
                    out.append(shortcode);
                }
                case TEXT -> {
                    if (tagStack.isEmpty() && shortcodeStack.isEmpty() && !isTrimEmpty(text, start, end)) {
                        out.append(segment(text.substring(start, end), strings));
                    } else {
                        out.append(text, start, end);
                    }
                }
            }
        });
        return out.toString();
    }

    /**
     * Opens or closes a zone. The name runs from after {@code <}, {@code [}, {@code </} or {@code [/} to the
     * first ASCII space, or without a space to just before the delimiter's last character. An opener whose name
     * is listed is pushed; a closer pops only when its name is on top.
     */
    static void pushOrPop(Deque<String> stack, String delimiter, Set<String> names) {
        boolean closing = delimiter.length() > 1 && delimiter.charAt(1) == '/';
        if (closing && stack.isEmpty()) {
            return;
        }
        int start = closing ? 2 : 1;
        int space = delimiter.indexOf(' ');
        int end = space >= 0 ? space : delimiter.length() - 1;
        String name = end > start ? delimiter.substring(start, end) : "";
        if (!names.contains(name)) {
            return;
        }
        if (!closing) {
            stack.push(name);
        } else if (name.equals(stack.peek())) {
            stack.pop();
        }
    }

    /** One text segment, texturized. */
    static String segment(String s, TexturizeStrings strings) {
        s = s.replace("...", ELLIPSIS);
        s = s.replace("``", strings.openingDoubleQuote());
        s = s.replace("''", strings.closingDoubleQuote());
        s = s.replace(" (tm)", TRADEMARK);
        for (Map.Entry<String, String> word : strings.cockney().entrySet()) {
            if (!word.getKey().isEmpty()) {
                s = s.replace(word.getKey(), word.getValue());
            }
        }
        if (s.indexOf('\'') >= 0) {
            s = QuoteRules.singles(s, strings);
            s = Primes.apply(s, '\'', strings.prime(), QuoteRules.OPENING_SINGLE, strings.closingSingleQuote());
            s = s.replace(QuoteRules.APOSTROPHE, strings.apostrophe());
            s = s.replace(QuoteRules.OPENING_SINGLE, strings.openingSingleQuote());
        }
        if (s.indexOf('"') >= 0) {
            s = QuoteRules.doubles(s, strings);
            s = Primes.apply(s, '"', strings.doublePrime(), QuoteRules.OPENING_DOUBLE, strings.closingDoubleQuote());
            s = s.replace(QuoteRules.OPENING_DOUBLE, strings.openingDoubleQuote());
        }
        if (s.indexOf('-') >= 0) {
            s = DashRules.dashes(s, strings.emDash(), strings.enDash());
        }
        s = DashRules.multiplication(s);
        return fixAmpersands(s);
    }

    /**
     * Every {@code &} that does not start {@code &#digits;}, {@code &#xhex;} or 1 to 8 letters and digits 1-4
     * followed by {@code ;} (ASCII, any case) becomes {@code &#038;}.
     */
    static String fixAmpersands(String s) {
        StringBuilder out = null;
        int copied = 0;
        for (int i = s.indexOf('&'); i >= 0; i = s.indexOf('&', i + 1)) {
            if (!startsEntity(s, i + 1)) {
                out = Edits.started(out, s);
                out.append(s, copied, i).append(AMPERSAND);
                copied = i + 1;
            }
        }
        return Edits.finished(out, s, copied);
    }

    private static boolean startsEntity(String s, int i) {
        int length = s.length();
        int j = i;
        if (j < length && s.charAt(j) == '#') {
            j++;
            if (j < length && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
                int k = j + 1;
                while (k < length && isHexDigit(s.charAt(k))) {
                    k++;
                }
                return k > j + 1 && k < length && s.charAt(k) == ';';
            }
            int k = j;
            while (k < length && Chars.isAsciiDigit(s.charAt(k))) {
                k++;
            }
            return k > j && k < length && s.charAt(k) == ';';
        }
        while (j < length && j - i < 8 && isNamedEntityChar(s.charAt(j))) {
            j++;
        }
        return j > i && j < length && s.charAt(j) == ';';
    }

    private static boolean isHexDigit(char c) {
        return Chars.isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isNamedEntityChar(char c) {
        return Chars.isAsciiLetter(c) || (c >= '1' && c <= '4');
    }

    private static boolean isTrimEmpty(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Chars.isPhpTrim(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}

package org.quarkpress.media.runtime;

import java.util.Set;
import org.quarkpress.security.runtime.RemoveAccents;

/**
 * Upload file names as WordPress cleans them before it stores a file: accents removed by the default locale's rules,
 * spaces made plain, characters that are unsafe in paths, URLs or shells removed, runs of dots, spaces and dashes
 * collapsed, the ends trimmed, a name that is only an extension renamed, and middle parts that look like an extension
 * the user may not upload marked with an underscore so that no server treats the file as that type. Case is kept.
 */
public final class FileNames {

    /** The name an extension-only name gets in front of its extension. */
    static final String UNNAMED = "unnamed-file";

    private FileNames() {}

    /**
     * The cleaned form of {@code clientName}. Every step is one pass over the code points, in WordPress's order.
     * WordPress also turns {@code %20} and {@code +} into dashes, which only a filter keeping {@code %} and {@code +}
     * could reach; QuarkPress has no such filter, so both are always removed. A Java string is always valid UTF-16, so
     * WordPress's fallback for names that are not valid UTF-8 does not apply.
     *
     * @param clientName the name the client sent
     * @param allowed the acting user's upload types, which decide whether a middle part is a known extension
     * @return the cleaned name; empty when nothing is left
     */
    public static String sanitize(String clientName, AllowedTypes allowed) {
        String name = RemoveAccents.enUs(clientName);
        name = plainSpaces(name);
        name = withoutUnsafeCharacters(name);
        name = collapseDots(name);
        name = collapseDashes(name);
        name = trimEnds(name);
        if (!name.isEmpty() && name.indexOf('.') < 0 && isExtension(name, DefaultMimeTypes.fullExtensions())) {
            name = UNNAMED + "." + name;
        }
        return markMiddleExtensions(name, allowed.extensions());
    }

    /** The part of {@code name} before its last dot; the name itself when it has no dot. */
    public static String withoutExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Every space separator (no-break, en, em, thin, ideographic and the others) becomes a plain space. */
    static String plainSpaces(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            out.appendCodePoint(isSpaceSeparator(c) ? ' ' : c);
        }
        return out.toString();
    }

    private static boolean isSpaceSeparator(int c) {
        return c == ' '
                || c == 0x00A0
                || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A)
                || c == 0x202F
                || c == 0x205F
                || c == 0x3000;
    }

    /** Removes the characters WordPress removes from file names. */
    static String withoutUnsafeCharacters(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); ) {
            int c = name.codePointAt(i);
            i += Character.charCount(c);
            if (!isUnsafe(c)) {
                out.appendCodePoint(c);
            }
        }
        return out.toString();
    }

    private static boolean isUnsafe(int c) {
        return switch (c) {
            case '?',
                    '[',
                    ']',
                    '/',
                    '\\',
                    '=',
                    '<',
                    '>',
                    ':',
                    ';',
                    ',',
                    '\'',
                    '"',
                    '&',
                    '$',
                    '#',
                    '*',
                    '(',
                    ')',
                    '|',
                    '~',
                    '`',
                    '!',
                    '{',
                    '}',
                    '%',
                    '+' -> true;
            // right single quote, both guillemets, both double quotes, NUL
            case 0x2019, 0x00AB, 0x00BB, 0x201D, 0x201C, 0x0000 -> true;
            default -> false;
        };
    }

    /** Runs of two or more dots become one. */
    static String collapseDots(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '.' || out.isEmpty() || out.charAt(out.length() - 1) != '.') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Runs of carriage returns, line feeds, tabs, spaces and dashes, in any mix, become one dash. */
    static String collapseDashes(String name) {
        StringBuilder out = new StringBuilder(name.length());
        boolean inRun = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean dashLike = c == '\r' || c == '\n' || c == '\t' || c == ' ' || c == '-';
            if (!dashLike) {
                out.append(c);
            } else if (!inRun) {
                out.append('-');
            }
            inRun = dashLike;
        }
        return out.toString();
    }

    /** Removes dots, dashes and underscores from both ends. */
    static String trimEnds(String name) {
        int start = 0;
        int end = name.length();
        while (start < end && isTrimmed(name.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return c == '.' || c == '-' || c == '_';
    }

    /**
     * Marks with {@code _} each middle part (not the first, not the last) of a name with three or more dot-separated
     * parts that looks like an extension (two to five ASCII letters, optionally followed by one digit) and is not one
     * of {@code allowedExtensions}.
     */
    static String markMiddleExtensions(String name, Set<String> allowedExtensions) {
        int first = name.indexOf('.');
        int last = name.lastIndexOf('.');
        if (first < 0 || first == last) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length() + 4);
        out.append(name, 0, first);
        int start = first + 1;
        while (start <= last) {
            int end = name.indexOf('.', start);
            String part = name.substring(start, end);
            out.append('.').append(part);
            if (looksLikeExtension(part) && !isExtension(part, allowedExtensions)) {
                out.append('_');
            }
            start = end + 1;
        }
        out.append(name, last, name.length());
        return out.toString();
    }

    private static boolean looksLikeExtension(String part) {
        int letters = 0;
        while (letters < part.length() && isAsciiLetter(part.charAt(letters))) {
            letters++;
        }
        if (letters < 2 || letters > 5) {
            return false;
        }
        int rest = part.length() - letters;
        return rest == 0 || (rest == 1 && part.charAt(letters) >= '0' && part.charAt(letters) <= '9');
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** Whether {@code text} is one of {@code extensions} (lower case), ignoring the case of ASCII letters only. */
    private static boolean isExtension(String text, Set<String> extensions) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return extensions.contains(lower.toString());
    }
}

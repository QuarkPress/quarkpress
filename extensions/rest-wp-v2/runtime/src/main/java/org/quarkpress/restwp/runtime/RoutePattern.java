package org.quarkpress.restwp.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A route pattern in WordPress's form, matched without regular expressions: literal text and the named groups
 * {@code (?P<name>[\d]+)} (ASCII digits) and {@code (?P<name>[\w-]+)} (ASCII letters, digits, {@code _} and
 * {@code -}), at most one group per path segment. Matching walks the path's segments once, without backtracking:
 * within a segment the group runs from the end of the literal prefix to the start of the literal suffix, which is the
 * only place the greedy group of the expression can end, so the work is linear in the path. Literals compare ignoring
 * ASCII case, as WordPress's case-insensitive route expressions do; like their {@code $}, the end of the pattern also
 * matches before one final line feed.
 */
public final class RoutePattern {

    private static final String DIGITS = "[\\d]+)";
    private static final String WORD = "[\\w-]+)";
    private static final String GROUP = "(?P<";

    /** A literal part, or a group when {@code name} is set. */
    private record Part(String literal, String name, boolean digits) {}

    /**
     * One path segment of the pattern: a literal, or a literal prefix, one group and a literal suffix ({@code name}
     * set).
     */
    private record Segment(String prefix, String name, boolean digits, String suffix) {}

    private final String key;
    private final String template;
    private final List<Segment> segments;

    private RoutePattern(String key, String template, List<Part> parts) {
        this.key = key;
        this.template = template;
        this.segments = segments(parts);
    }

    /** The parts cut at each {@code /}; parsing allows at most one group per segment. */
    private static List<Segment> segments(List<Part> parts) {
        List<Segment> segments = new ArrayList<>();
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        String name = null;
        boolean digits = false;
        for (Part part : parts) {
            if (part.name() != null) {
                name = part.name();
                digits = part.digits();
                continue;
            }
            String literal = part.literal();
            for (int i = 0; i < literal.length(); i++) {
                char c = literal.charAt(i);
                if (c == '/') {
                    segments.add(new Segment(prefix.toString(), name, digits, suffix.toString()));
                    prefix.setLength(0);
                    suffix.setLength(0);
                    name = null;
                } else {
                    (name == null ? prefix : suffix).append(c);
                }
            }
        }
        segments.add(new Segment(prefix.toString(), name, digits, suffix.toString()));
        return List.copyOf(segments);
    }

    /**
     * The route {@code pattern} below {@code namespace}; both are trimmed of slashes and joined as WordPress joins
     * them ({@code /namespace/pattern}).
     *
     * @throws IllegalArgumentException when either holds anything but literal text and the two supported groups
     */
    public static RoutePattern parse(String namespace, String pattern) {
        String ns = trimSlashes(namespace);
        String below = trimSlashes(pattern);
        String full = ns.isEmpty() ? "/" + below : below.isEmpty() ? "/" + ns : "/" + ns + "/" + below;
        for (int i = 0; i < ns.length(); i++) {
            if (!literal(ns.charAt(i)) && ns.charAt(i) != '.') {
                throw new IllegalArgumentException("unsupported route pattern: " + namespace);
            }
        }
        List<Part> parts = new ArrayList<>();
        Set<String> names = new HashSet<>();
        StringBuilder template = new StringBuilder();
        StringBuilder text = new StringBuilder();
        boolean groupInSegment = false;
        int i = 0;
        while (i < full.length()) {
            char c = full.charAt(i);
            if (c == '(') {
                int nameEnd = full.indexOf('>', i);
                if (!full.startsWith(GROUP, i) || nameEnd < 0) {
                    throw unsupported(pattern);
                }
                String name = full.substring(i + GROUP.length(), nameEnd);
                if (name.isEmpty() || !groupName(name) || !names.add(name)) {
                    throw unsupported(pattern);
                }
                boolean digits;
                if (full.startsWith(DIGITS, nameEnd + 1)) {
                    digits = true;
                    i = nameEnd + 1 + DIGITS.length();
                } else if (full.startsWith(WORD, nameEnd + 1)) {
                    digits = false;
                    i = nameEnd + 1 + WORD.length();
                } else {
                    throw unsupported(pattern);
                }
                if (groupInSegment) {
                    throw new IllegalArgumentException("unsupported route pattern: " + pattern
                            + " (two groups in one path segment could make matching take quadratic time)");
                }
                groupInSegment = true;
                if (!text.isEmpty()) {
                    parts.add(new Part(text.toString(), null, false));
                    text.setLength(0);
                }
                parts.add(new Part(null, name, digits));
                template.append('{').append(name).append('}');
                continue;
            }
            if (!literal(c) && !(c == '.' && i <= ns.length())) {
                throw unsupported(pattern);
            }
            if (c == '/') {
                groupInSegment = false;
            }
            text.append(c);
            template.append(c);
            i++;
        }
        if (!text.isEmpty()) {
            parts.add(new Part(text.toString(), null, false));
        }
        return new RoutePattern(full, template.toString(), List.copyOf(parts));
    }

    private static IllegalArgumentException unsupported(String pattern) {
        return new IllegalArgumentException("unsupported route pattern: " + pattern);
    }

    private static boolean literal(char c) {
        return asciiLetterOrDigit(c) || c == '/' || c == '-' || c == '_';
    }

    private static boolean groupName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!asciiLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return !(name.charAt(0) >= '0' && name.charAt(0) <= '9');
    }

    private static boolean asciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    static String trimSlashes(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && text.charAt(start) == '/') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(start, end);
    }

    /** WordPress's key for the route: its full pattern, such as {@code /wp/v2/categories/(?P<id>[\d]+)}. */
    public String key() {
        return key;
    }

    /** The route with its groups as {@code {name}}, such as {@code /wp/v2/categories/{id}}. */
    public String template() {
        return template;
    }

    /** The groups' values when {@code path} matches the whole pattern; empty otherwise. */
    public Optional<Map<String, String>> match(String path) {
        return match(path, new long[1]);
    }

    /**
     * {@link #match(String)}, adding to {@code examined[0]} the path characters looked at. Each segment of the path
     * is found once and checked once, without backtracking: the group of a segment ends where the segment's literal
     * suffix begins, so the work is linear (at most a few looks per character).
     */
    Optional<Map<String, String>> match(String path, long[] examined) {
        int length = path.length();
        // like the route expressions' $, the end also matches before one final line feed (no part matches one)
        if (length > 0 && path.charAt(length - 1) == '\n') {
            length--;
        }
        int[] bounds = new int[segments.size() * 2];
        int at = 0;
        for (int index = 0; index < segments.size(); index++) {
            int end = at;
            while (end < length && path.charAt(end) != '/') {
                examined[0]++;
                end++;
            }
            boolean last = index == segments.size() - 1;
            if (last != (end == length)) {
                return Optional.empty();
            }
            Segment segment = segments.get(index);
            String prefix = segment.prefix();
            if (segment.name() == null) {
                if (end - at != prefix.length() || !sameAscii(path, at, prefix, examined)) {
                    return Optional.empty();
                }
            } else {
                String suffix = segment.suffix();
                int groupStart = at + prefix.length();
                int groupEnd = end - suffix.length();
                if (groupEnd <= groupStart
                        || !sameAscii(path, at, prefix, examined)
                        || !sameAscii(path, groupEnd, suffix, examined)) {
                    return Optional.empty();
                }
                for (int i = groupStart; i < groupEnd; i++) {
                    examined[0]++;
                    if (!inGroup(path.charAt(i), segment.digits())) {
                        return Optional.empty();
                    }
                }
                bounds[index * 2] = groupStart;
                bounds[index * 2 + 1] = groupEnd;
            }
            at = end + 1;
        }
        Map<String, String> groups = new LinkedHashMap<>();
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment.name() != null) {
                groups.put(segment.name(), path.substring(bounds[index * 2], bounds[index * 2 + 1]));
            }
        }
        return Optional.of(groups);
    }

    /**
     * Whether {@code path} holds {@code literal} at {@code at}, ASCII letters compared ignoring case and every other
     * character exactly (a non-ASCII letter never stands for an ASCII one, as in the route expressions).
     */
    private static boolean sameAscii(String path, int at, String literal, long[] examined) {
        for (int i = 0; i < literal.length(); i++) {
            examined[0]++;
            char a = path.charAt(at + i);
            char b = literal.charAt(i);
            if (a != b && lowerAscii(a) != lowerAscii(b)) {
                return false;
            }
        }
        return true;
    }

    private static char lowerAscii(char c) {
        return c >= 'A' && c <= 'Z' ? (char) (c + 32) : c;
    }

    private static boolean inGroup(char c, boolean digits) {
        if (c >= '0' && c <= '9') {
            return true;
        }
        return !digits && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == '-');
    }

    @Override
    public String toString() {
        return key;
    }
}

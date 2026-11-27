package org.quarkword.lib.wpformatting;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * WordPress's default smiley table: 43 codes, each replaced by a raw emoji or, for an image file name, by an
 * {@code <img>}. The codes are kept grouped by their first character, longest first within each group, which is
 * the order the matcher tries them in.
 */
final class SmileyTable {

    /** One code and its replacement: an emoji, or the file name of an image. */
    record Smiley(String code, String replacement) {}

    /** The table in WordPress's order. {@code 8)} is absent on purpose: it would break text such as "(item 8)". */
    private static final List<Smiley> WORDPRESS_ORDER = List.of(
            new Smiley(":mrgreen:", "mrgreen.png"),
            new Smiley(":neutral:", "\ud83d\ude10"),
            new Smiley(":twisted:", "\ud83d\ude08"),
            new Smiley(":arrow:", "\u27a1"),
            new Smiley(":shock:", "\ud83d\ude2f"),
            new Smiley(":smile:", "\ud83d\ude42"),
            new Smiley(":???:", "\ud83d\ude15"),
            new Smiley(":cool:", "\ud83d\ude0e"),
            new Smiley(":evil:", "\ud83d\udc7f"),
            new Smiley(":grin:", "\ud83d\ude00"),
            new Smiley(":idea:", "\ud83d\udca1"),
            new Smiley(":oops:", "\ud83d\ude33"),
            new Smiley(":razz:", "\ud83d\ude1b"),
            new Smiley(":roll:", "\ud83d\ude44"),
            new Smiley(":wink:", "\ud83d\ude09"),
            new Smiley(":cry:", "\ud83d\ude25"),
            new Smiley(":eek:", "\ud83d\ude2e"),
            new Smiley(":lol:", "\ud83d\ude06"),
            new Smiley(":mad:", "\ud83d\ude21"),
            new Smiley(":sad:", "\ud83d\ude41"),
            new Smiley("8-)", "\ud83d\ude0e"),
            new Smiley("8-O", "\ud83d\ude2f"),
            new Smiley(":-(", "\ud83d\ude41"),
            new Smiley(":-)", "\ud83d\ude42"),
            new Smiley(":-?", "\ud83d\ude15"),
            new Smiley(":-D", "\ud83d\ude00"),
            new Smiley(":-P", "\ud83d\ude1b"),
            new Smiley(":-o", "\ud83d\ude2e"),
            new Smiley(":-x", "\ud83d\ude21"),
            new Smiley(":-|", "\ud83d\ude10"),
            new Smiley(";-)", "\ud83d\ude09"),
            new Smiley("8O", "\ud83d\ude2f"),
            new Smiley(":(", "\ud83d\ude41"),
            new Smiley(":)", "\ud83d\ude42"),
            new Smiley(":?", "\ud83d\ude15"),
            new Smiley(":D", "\ud83d\ude00"),
            new Smiley(":P", "\ud83d\ude1b"),
            new Smiley(":o", "\ud83d\ude2e"),
            new Smiley(":x", "\ud83d\ude21"),
            new Smiley(":|", "\ud83d\ude10"),
            new Smiley(";)", "\ud83d\ude09"),
            new Smiley(":!:", "\u2757"),
            new Smiley(":?:", "\u2753"));

    /** The table grouped by first character (groups in order of first appearance), longest first in each. */
    static final List<Smiley> MATCH_ORDER = matchOrder();

    /** The codes of {@link #MATCH_ORDER}. */
    static final List<String> CODES = MATCH_ORDER.stream().map(Smiley::code).toList();

    /** Every first character of a code, once each. */
    private static final String FIRST_CHARACTERS = firstCharacters();

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "jpe", "gif", "png", "webp", "avif");

    private SmileyTable() {}

    private static String firstCharacters() {
        StringBuilder firsts = new StringBuilder();
        for (Smiley s : MATCH_ORDER) {
            if (firsts.indexOf(s.code().substring(0, 1)) < 0) {
                firsts.append(s.code().charAt(0));
            }
        }
        return firsts.toString();
    }

    private static List<Smiley> matchOrder() {
        Set<Character> firsts = new LinkedHashSet<>();
        WORDPRESS_ORDER.forEach(s -> firsts.add(s.code().charAt(0)));
        List<Smiley> ordered = new ArrayList<>();
        for (char first : firsts) {
            List<Smiley> group = new ArrayList<>();
            WORDPRESS_ORDER.stream().filter(s -> s.code().charAt(0) == first).forEach(group::add);
            group.sort((a, b) -> Integer.compare(b.code().length(), a.code().length()));
            ordered.addAll(group);
        }
        return List.copyOf(ordered);
    }

    /**
     * The index in {@link #MATCH_ORDER} of the longest code that starts at {@code at} and ends at or before
     * {@code end} and for which {@code endsAtBoundary} holds at its end, or -1.
     */
    static int match(String text, int at, int end, Boundary endsAtBoundary) {
        char first = text.charAt(at);
        for (int k = 0; k < MATCH_ORDER.size(); k++) {
            String code = MATCH_ORDER.get(k).code();
            if (code.charAt(0) != first) {
                continue;
            }
            int codeEnd = at + code.length();
            if (codeEnd <= end && text.startsWith(code, at) && endsAtBoundary.test(codeEnd)) {
                return k;
            }
        }
        return -1;
    }

    /** True when a code can start with {@code c}. */
    static boolean canStart(char c) {
        return FIRST_CHARACTERS.indexOf(c) >= 0;
    }

    /** True when the replacement names an image file (by its extension, compared in ASCII lower case). */
    static boolean isImageFile(String replacement) {
        int dot = replacement.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        StringBuilder extension = new StringBuilder();
        for (int i = dot + 1; i < replacement.length(); i++) {
            char c = replacement.charAt(i);
            extension.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return IMAGE_EXTENSIONS.contains(extension.toString());
    }

    /** A test on the position just after a candidate code. */
    @FunctionalInterface
    interface Boundary {
        boolean test(int position);
    }
}

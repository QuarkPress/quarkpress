package org.quarkpress.lib.wpshortcode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the names worth scanning for (R2.3): those equal to some candidate, where a candidate is the longest
 * non-empty run of name chars right after a {@code [}. A name followed by a char such as {@code .} or {@code :}
 * therefore counts only if the same name also appears followed by a terminator elsewhere.
 */
final class TagPrefilter {

    private TagPrefilter() {}

    /** The names, in the given order, that equal some candidate in {@code content}. */
    static List<String> active(String content, List<String> names) {
        Set<String> wanted = new HashSet<>(names);
        int longest = 0;
        for (String name : names) {
            longest = Math.max(longest, name.length());
        }
        Set<String> seen = new HashSet<>();
        int n = content.length();
        for (int i = content.indexOf('['); i >= 0; i = content.indexOf('[', i + 1)) {
            int j = i + 1;
            while (j < n && !Chars.endsName(content.charAt(j))) {
                j++;
            }
            int length = j - i - 1;
            if (length > 0 && length <= longest) {
                String candidate = content.substring(i + 1, j);
                if (wanted.contains(candidate)) {
                    seen.add(candidate);
                }
            }
        }
        List<String> active = new ArrayList<>();
        for (String name : names) {
            if (seen.contains(name)) {
                active.add(name);
            }
        }
        return active;
    }
}

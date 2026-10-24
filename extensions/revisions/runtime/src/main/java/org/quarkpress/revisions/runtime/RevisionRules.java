package org.quarkpress.revisions.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.quarkpress.core.RevisionId;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionSnapshot;

/** Rules of WordPress's revision saving that need no database. */
public final class RevisionRules {

    private RevisionRules() {}

    /** The number of revisions to keep: the configured limit, or 0 when the post type does not support revisions. */
    public static int keep(int configured, boolean typeSupportsRevisions) {
        return typeSupportsRevisions ? configured : 0;
    }

    /** Whether revisions are kept at all. */
    public static boolean enabled(int keep) {
        return keep != 0;
    }

    /**
     * Trims space, tab, line feed, carriage return, NUL and vertical tab from both ends, turns every carriage return
     * into a line feed, and collapses a run of line feeds into one and a run of spaces and tabs into one space.
     */
    public static String normalizeWhitespace(String text) {
        Objects.requireNonNull(text, "text");
        int start = 0;
        int end = text.length();
        while (start < end && isTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(text.charAt(end - 1))) {
            end--;
        }
        StringBuilder out = new StringBuilder(end - start);
        char previous = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                c = '\n';
            }
            if (c == '\t') {
                c = ' ';
            }
            boolean run = (c == '\n' && previous == '\n') || (c == ' ' && previous == ' ');
            if (!run) {
                out.append(c);
            }
            previous = c;
        }
        return out.toString();
    }

    private static boolean isTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    /** The post's value of a revisioned field. */
    public static String field(RevisionField field, PostSnapshot post) {
        return switch (field) {
            case TITLE -> post.title();
            case CONTENT -> post.content();
            case EXCERPT -> post.excerpt();
        };
    }

    /** The revision's value of a revisioned field. */
    public static String field(RevisionField field, RevisionSnapshot revision) {
        return switch (field) {
            case TITLE -> revision.title();
            case CONTENT -> revision.content();
            case EXCERPT -> revision.excerpt();
        };
    }

    /** What a new autosave stores for a field: the given value, or nothing when the field was not given. */
    public static String autosaveField(AutosaveChanges changes, RevisionField field) {
        return changes.field(field).orElse("");
    }

    /** Whether any of the fields differs between the latest regular revision and the post, whitespace aside. */
    public static boolean fieldsChanged(Set<RevisionField> fields, RevisionSnapshot last, PostSnapshot current) {
        for (RevisionField field : fields) {
            if (!normalizeWhitespace(field(field, current)).equals(normalizeWhitespace(field(field, last)))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the values of any of the keys differ, comparing the lists in order and the values by value. */
    public static boolean metaChanged(
            Set<String> keys, Map<String, List<JsonNode>> last, Map<String, List<JsonNode>> current) {
        for (String key : keys) {
            List<JsonNode> before = last.getOrDefault(key, List.of());
            List<JsonNode> after = current.getOrDefault(key, List.of());
            if (before.size() != after.size()) {
                return true;
            }
            for (int i = 0; i < before.size(); i++) {
                if (!JsonValues.sameValue(before.get(i), after.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an autosave of the given changes differs from the post: a given field in {@code fields} differs after
     * normalization, or, when meta is given, any revisioned key is not stored by the post or its first stored value
     * differs from the given one (an empty text when the key is not given).
     */
    public static boolean autosaveDiffers(
            Set<RevisionField> fields, Set<String> metaKeys, AutosaveChanges changes, PostSnapshot post) {
        for (RevisionField field : fields) {
            if (changes.field(field)
                    .filter(given -> !normalizeWhitespace(given).equals(normalizeWhitespace(field(field, post))))
                    .isPresent()) {
                return true;
            }
        }
        if (changes.meta().isPresent()) {
            Map<String, JsonNode> given = changes.meta().get();
            for (String key : metaKeys) {
                List<JsonNode> stored = post.meta(key);
                if (stored.isEmpty()) {
                    return true;
                }
                JsonNode before = stored.get(0);
                JsonNode after = given.getOrDefault(key, TextNode.valueOf(""));
                if (!JsonValues.sameValue(before, after)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The revisions to delete after a save, given all of a post's revisions oldest first: the first entries beyond
     * the limit that are not autosaves (a skipped autosave is not replaced by a later entry).
     */
    public static List<RevisionId> trimCandidates(List<RevisionSnapshot> oldestFirst, int keep) {
        if (keep < 0) {
            return List.of();
        }
        int delete = oldestFirst.size() - keep;
        if (delete < 1) {
            return List.of();
        }
        List<RevisionId> ids = new ArrayList<>();
        for (RevisionSnapshot revision : oldestFirst.subList(0, delete)) {
            if (!revision.autosave()) {
                ids.add(revision.id());
            }
        }
        return List.copyOf(ids);
    }
}

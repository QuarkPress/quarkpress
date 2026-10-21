package org.quarkpress.taxonomy.runtime;

import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.TermException;

/** Field rules of WordPress's term save that need no database. */
public final class TermRules {

    /** The characters the name column holds (WordPress's varchar(200)). */
    public static final int MAX_NAME = 200;

    /** The characters the slug column holds. */
    public static final int MAX_SLUG = 200;

    private TermRules() {}

    /** PHP's {@code empty()} for text: "" and "0". */
    public static boolean phpEmpty(String text) {
        return text.isEmpty() || text.equals("0");
    }

    /** The parent a caller gives: absent, 0 and negative ids mean none, as WordPress casts them. */
    public static Optional<TermId> parent(OptionalLong given) {
        return given.isPresent() && given.getAsLong() > 0
                ? Optional.of(TermId.of(given.getAsLong()))
                : Optional.empty();
    }

    /**
     * Refuses values the columns cannot hold, as WordPress's failed database write reports them: a name or slug longer
     * than 200 characters, and a NUL in the name or the description (PostgreSQL cannot store one; it is refused on
     * both databases alike).
     */
    public static void requireStorable(String name, String slug, String description, boolean update) {
        if (name.codePointCount(0, name.length()) > MAX_NAME) {
            throw fail(update, "the name is longer than " + MAX_NAME + " characters");
        }
        if (slug.codePointCount(0, slug.length()) > MAX_SLUG) {
            throw fail(update, "the slug is longer than " + MAX_SLUG + " characters");
        }
        if (TermText.hasNul(name)) {
            throw fail(update, "the name holds a NUL character");
        }
        if (TermText.hasNul(description)) {
            throw fail(update, "the description holds a NUL character");
        }
    }

    private static TermException fail(boolean update, String detail) {
        return update ? TermException.dbUpdateError(detail) : TermException.dbError(detail);
    }
}

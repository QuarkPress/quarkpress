package org.quarkpress.taxonomy.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.TermId;

/**
 * WordPress's duplicate-name check of a new term. A name matches a stored term's when both are equal after lowering the
 * letters A to Z only (accents and other letters are compared as they are). Without a match there is no duplicate.
 * With one, the new term is a duplicate only when no slug was given, or the given slug is the match's slug, or another
 * term of the taxonomy holds it; then in a flat taxonomy the match is the duplicate, and in a hierarchical taxonomy a
 * sibling must hold the exact name (case included, checked only when no other slug was given) or the slug.
 */
public final class DuplicateNames {

    /** A stored term as the check sees it. */
    public record Stored(TermId id, String name, String slug, Optional<TermId> parent) {
        public Stored {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(parent, "parent");
        }
    }

    private DuplicateNames() {}

    /**
     * The term the new name collides with, if any.
     *
     * @param name the sanitized new name
     * @param slug the slug before it is made unique: the given one, or the one generated from the name
     * @param slugProvided whether the caller gave a slug that is not empty in PHP's sense after sanitizing
     * @param parent the requested parent (empty: top level)
     * @param sameParentCandidates the taxonomy's terms with that parent whose names have the new name's length, in
     *     name order (lower-cased, by code point, then id): every possible match and every exact sibling name is among
     *     them
     * @param slugMatch the taxonomy's term (any parent) whose slug is {@code slug}
     */
    public static Optional<TermId> conflict(
            String name,
            String slug,
            boolean slugProvided,
            boolean hierarchical,
            Optional<TermId> parent,
            List<Stored> sameParentCandidates,
            Optional<Stored> slugMatch) {
        String folded = TermText.asciiLower(name);
        Optional<Stored> nameMatch = sameParentCandidates.stream()
                .filter(candidate -> TermText.asciiLower(candidate.name()).equals(folded))
                .findFirst();
        if (nameMatch.isEmpty()) {
            return Optional.empty();
        }
        boolean sameSlug = nameMatch.get().slug().equals(slug);
        if (slugProvided && !sameSlug && slugMatch.isEmpty()) {
            return Optional.empty();
        }
        if (!hierarchical) {
            return Optional.of(nameMatch.get().id());
        }
        boolean exactSibling = sameParentCandidates.stream()
                .anyMatch(candidate -> candidate.name().equals(name));
        if ((!slugProvided || sameSlug) && exactSibling) {
            return Optional.of(nameMatch.get().id());
        }
        if (slugMatch.isPresent() && slugMatch.get().parent().equals(parent)) {
            return Optional.of(slugMatch.get().id());
        }
        return Optional.empty();
    }
}

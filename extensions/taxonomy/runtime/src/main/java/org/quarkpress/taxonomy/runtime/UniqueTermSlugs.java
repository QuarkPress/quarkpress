package org.quarkpress.taxonomy.runtime;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.TermId;

/**
 * WordPress's unique term slugs. A slug needs a suffix only when a term of the same taxonomy holds it. Then, in a
 * hierarchical taxonomy, the parent's slug is appended, and the grandparent's and so on while the result is the slug
 * or the name of any term of the site; then, if any term of the site (any taxonomy) other than the term itself holds
 * the result, {@code -2}, {@code -3}, ... are tried until one is free everywhere.
 */
public final class UniqueTermSlugs {

    /** What the rule reads from the site's terms. */
    public interface Lookup {

        /** Whether a term of the taxonomy holds {@code slug}. */
        boolean takenInTaxonomy(String slug);

        /** The slug and parent of a term of the taxonomy; empty when there is no such term. */
        Optional<Parent> parent(TermId id);

        /**
         * Whether any term of the site has {@code slugOrName} as slug, or as name ignoring case. As in WordPress's
         * {@code term_exists}, the probe is first normalised like a stored name (text-field sanitising, which
         * strips {@code %xx} octets) before it is compared with names.
         */
        boolean existsAnywhere(String slugOrName);

        /** Whether any term of the site other than {@code except} holds {@code slug}. */
        boolean slugTakenAnywhere(String slug, Optional<TermId> except);
    }

    /** A parent term as the rule sees it. */
    public record Parent(String slug, Optional<TermId> parent) {
        public Parent {
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(parent, "parent");
        }
    }

    private UniqueTermSlugs() {}

    /**
     * The unique form of {@code slug} for a term (being inserted when {@code self} is empty) of a taxonomy that is
     * {@code hierarchical}, under {@code parent}.
     */
    public static String unique(
            String slug, boolean hierarchical, Optional<TermId> parent, Optional<TermId> self, Lookup lookup) {
        if (!lookup.takenInTaxonomy(slug)) {
            return slug;
        }
        StringBuilder suffix = new StringBuilder();
        if (hierarchical) {
            Optional<TermId> next = parent;
            while (next.isPresent()) {
                Optional<Parent> term = lookup.parent(next.get());
                if (term.isEmpty()) {
                    break;
                }
                suffix.append('-').append(term.get().slug());
                if (!lookup.existsAnywhere(slug + suffix)) {
                    break;
                }
                next = term.get().parent();
            }
        }
        String candidate = slug + suffix;
        if (!lookup.slugTakenAnywhere(candidate, self)) {
            return candidate;
        }
        int number = 2;
        while (lookup.slugTakenAnywhere(candidate + "-" + number, Optional.empty())) {
            number++;
        }
        return candidate + "-" + number;
    }
}

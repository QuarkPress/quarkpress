package org.quarkpress.taxonomy.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.SlugSanitizer;

/**
 * Term slugs through the posts module's {@link SlugSanitizer} (WordPress's {@code sanitize_title}), so an application
 * that replaces it changes post and term slugs alike. The context of a term slug has no post and no parent, the
 * taxonomy as type and an empty status.
 */
@ApplicationScoped
public class TermSlugs {

    private final SlugSanitizer sanitizer;

    @Inject
    public TermSlugs(SlugSanitizer sanitizer) {
        this.sanitizer = sanitizer;
    }

    /**
     * Sanitizes a slug the caller gave. WordPress sanitizes it while it is still slashed (a backslash, quote or double
     * quote gets a backslash and a NUL becomes a backslash and a zero) and keeps the result as it is, so the text is
     * slashed first.
     */
    public String sanitize(SiteId site, String taxonomy, String text) {
        return generate(site, taxonomy, TermText.addSlashes(text), text);
    }

    /** Sanitizes the slug made from a stored name: WordPress hands the unslashed name to the sanitizer. */
    public String generate(SiteId site, String taxonomy, String name) {
        return generate(site, taxonomy, name, name);
    }

    private String generate(SiteId site, String taxonomy, String text, String original) {
        return sanitizer.sanitize(
                text, new SlugContext(site, Optional.empty(), taxonomy, "", Optional.empty(), original));
    }
}

package org.quarkpress.posts.runtime;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.posts.SlugContext;
import org.quarkpress.posts.SlugSanitizer;

/** WordPress's {@code sanitize_title} in its save context, with the {@code en_US} accent rules. */
@DefaultBean
@ApplicationScoped
public class DefaultSlugSanitizer implements SlugSanitizer {

    @Override
    public String sanitize(String text, SlugContext context) {
        return Slugs.sanitizeTitle(text);
    }
}

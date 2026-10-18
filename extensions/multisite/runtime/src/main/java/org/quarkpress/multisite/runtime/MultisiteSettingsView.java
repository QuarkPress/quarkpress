package org.quarkpress.multisite.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/** The multisite settings, as the rest of the module reads them. */
@ApplicationScoped
public class MultisiteSettingsView {

    /** The largest accepted {@link #pathSegments()}. */
    public static final int MAX_PATH_SEGMENTS = 16;

    @Inject
    MultisiteConfig config;

    /** The enabled built-in strategies ({@code quarkpress.multisite.resolvers}). */
    public Set<ResolverStrategy> resolvers() {
        return config.resolvers();
    }

    /** {@code quarkpress.multisite.path-segments}. */
    public int pathSegments() {
        return config.pathSegments();
    }
}

package org.quarkpress.core.runtime;

import org.quarkpress.core.SiteFeatureDisabledException;

/**
 * Thrown when a REST resource method of a disabled feature is called. With Quarkus REST present, the build registers a
 * mapper that answers it with an empty 404, so the response does not reveal the feature. The message does not name the
 * feature (it stays available through {@link #key()}). A catch-all {@code ExceptionMapper<Throwable>} of the
 * application does not override that mapper, because JAX-RS picks the most specific one. A non-endpoint public method
 * of a gated {@code @Path} class throws it too when called internally.
 */
public final class SiteFeatureNotFoundException extends SiteFeatureDisabledException {

    private static final long serialVersionUID = 1L;

    public SiteFeatureNotFoundException(String key) {
        super(key, "Not found");
    }
}

package org.quarkpress.core.runtime;

import java.net.URI;
import java.util.Optional;

/**
 * The site an HTTP layer decided for the current request before any QuarkPress code ran. {@link SiteBinding} adopts
 * it instead of asking the resolvers. Implementations read only what the HTTP layer stored for the request; they must
 * not read the current site. Not for application code.
 */
public interface PresetSite {

    /** The binding decided for the current request, or empty when nothing was decided. */
    Optional<Binding> binding();

    /** Where the request should be redirected, decided together with the binding. */
    Optional<URI> redirect();
}

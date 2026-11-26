package org.quarkword.posts;

import java.util.Optional;

/**
 * The query variable of a registered taxonomy ({@code genre} for {@code ?genre=rock}), for the plain term links of
 * the default {@link LinkBuilder}; the taxonomy module provides it. Without it only the built-in taxonomies' query
 * variables are known.
 */
public interface TermQueryVariables {

    /** The query variable of {@code taxonomy}; empty for a taxonomy without one or not registered. */
    Optional<String> of(String taxonomy);
}

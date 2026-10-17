package org.quarkpress.core;

import java.util.Optional;

/**
 * Finds the site a request belongs to. All resolver beans are asked in order of {@code @Priority}, <b>higher values
 * first</b> (the order ArC uses for {@code @All} lists, which is the opposite of WordPress hook priorities); the first
 * non-empty answer wins. Resolvers with equal
 * priority are asked in order of fully qualified class name. A resolver that throws stops the resolution.
 *
 * <p>For {@link SiteRequest#none()} a resolver must answer without using the QuarkPress persistence unit, because the
 * answer decides which site that unit's session belongs to.
 */
public interface SiteResolver {

    Optional<SiteId> resolve(SiteRequest request);
}

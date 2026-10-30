package org.quarkpress.security;

/**
 * What a member listing is ordered by: {@link #NAME} the display name, {@link #SLUG} the nicename,
 * {@link #REGISTERED} the profile's creation time, {@link #EMAIL} the e-mail address (members without one first),
 * {@link #URL} the id (QuarkPress keeps no user URL), {@link #INCLUDE} the position in the query's {@code include}
 * list (without one, the nicename), {@link #INCLUDE_SLUGS} the position in its {@code slugs} list, always ascending
 * as in WordPress. Text orders ignore case.
 */
public enum MemberOrder {
    ID,
    INCLUDE,
    NAME,
    REGISTERED,
    SLUG,
    INCLUDE_SLUGS,
    EMAIL,
    URL
}

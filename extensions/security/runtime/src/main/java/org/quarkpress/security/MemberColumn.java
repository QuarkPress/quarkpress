package org.quarkpress.security;

/**
 * A column a member search looks in, as WordPress's user search names them. {@link #LOGIN} is the nicename, as
 * QuarkPress has no separate login name; {@link #URL} matches nothing, as QuarkPress keeps no user URL.
 */
public enum MemberColumn {
    ID,
    LOGIN,
    NICENAME,
    DISPLAY_NAME,
    EMAIL,
    URL
}

package org.quarkpress.security.runtime;

import java.util.List;

/** The users of the dev realm, with the ids Keycloak gives them (their OIDC subjects) and their dev roles. */
public final class DevUsers {

    /** A dev user; {@code networkAdmin} users administer the network (on a single site, the main site). */
    public record DevUser(
            String id, String username, String givenName, String familyName, String role, boolean networkAdmin) {}

    public static final List<DevUser> ALL = List.of(
            new DevUser("a11ce000-0000-4000-8000-000000000001", "alice", "Alice", "Anders", "administrator", true),
            new DevUser("b0b00000-0000-4000-8000-000000000002", "bob", "Bob", "Baker", "editor", false),
            new DevUser("ca401000-0000-4000-8000-000000000003", "carol", "Carol", "Chen", "author", false),
            new DevUser("da7e0000-0000-4000-8000-000000000004", "dave", "Dave", "Diaz", "contributor", false),
            new DevUser("e4100000-0000-4000-8000-000000000005", "erin", "Erin", "Evans", "subscriber", false));

    private DevUsers() {}
}

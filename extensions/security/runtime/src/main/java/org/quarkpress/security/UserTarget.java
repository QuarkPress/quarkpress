package org.quarkpress.security;

import java.util.Objects;
import org.quarkpress.core.UserId;

/** The user a user capability ({@code edit_user}, {@code promote_user}, {@code remove_user}, ...) is checked against. */
public record UserTarget(UserId user) implements CapObject {

    public UserTarget {
        Objects.requireNonNull(user, "user");
    }
}

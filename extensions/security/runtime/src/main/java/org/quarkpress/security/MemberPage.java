package org.quarkpress.security;

import java.util.List;
import java.util.Objects;
import org.quarkpress.core.UserId;

/** One page of a member listing: the members in order and how many match in all. */
public record MemberPage(List<UserId> users, long total) {

    public MemberPage {
        users = List.copyOf(Objects.requireNonNull(users, "users"));
    }
}

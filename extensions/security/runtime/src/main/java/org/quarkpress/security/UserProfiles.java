package org.quarkpress.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.UserId;

/** Reads user profiles. Reads need no request context. */
public interface UserProfiles {

    Optional<UserProfile> find(UserId user);

    Optional<UserProfile> findBySubject(String subject);

    Optional<UserProfile> findByNicename(String nicename);

    /** The profiles of {@code users} in the given order, each once; unknown ids are skipped. */
    List<UserProfile> findAll(Collection<UserId> users);
}

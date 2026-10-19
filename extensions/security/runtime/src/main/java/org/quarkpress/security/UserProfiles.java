package org.quarkpress.security;

import java.util.Optional;
import org.quarkpress.core.UserId;

/** Reads user profiles. Reads need no request context. */
public interface UserProfiles {

    Optional<UserProfile> find(UserId user);

    Optional<UserProfile> findBySubject(String subject);

    Optional<UserProfile> findByNicename(String nicename);
}

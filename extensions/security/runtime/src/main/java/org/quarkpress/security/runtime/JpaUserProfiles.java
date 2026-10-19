package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Optional;
import org.quarkpress.core.UserId;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;

/** Reads profiles from qp_user_profile. */
@ApplicationScoped
public class JpaUserProfiles implements UserProfiles {

    @Inject
    UserProfileRepository profiles;

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<UserProfile> find(UserId user) {
        return profiles.findByIdOptional(user.value()).map(UserProfileEntity::toProfile);
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<UserProfile> findBySubject(String subject) {
        if (subject.isEmpty()
                || Character.isWhitespace(subject.charAt(0))
                || Character.isWhitespace(subject.charAt(subject.length() - 1))) {
            // no profile has such a subject, but MySQL's PAD SPACE collation would match "abc " to "abc"
            return Optional.empty();
        }
        return profiles.findBySubject(subject).map(UserProfileEntity::toProfile);
    }

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<UserProfile> findByNicename(String nicename) {
        return profiles.findByNicename(nicename).map(UserProfileEntity::toProfile);
    }
}

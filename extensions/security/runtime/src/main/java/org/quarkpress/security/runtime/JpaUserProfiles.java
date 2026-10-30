package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    @Override
    @ActivateRequestContext
    @Transactional
    public List<UserProfile> findAll(Collection<UserId> users) {
        List<Long> ids = new ArrayList<>();
        for (UserId user : new LinkedHashSet<>(users)) {
            ids.add(user.value());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, UserProfile> found = new HashMap<>();
        for (UserProfileEntity row : profiles.list("id in ?1", ids)) {
            found.put(row.id, row.toProfile());
        }
        List<UserProfile> ordered = new ArrayList<>();
        for (Long id : ids) {
            UserProfile profile = found.get(id);
            if (profile != null) {
                ordered.add(profile);
            }
        }
        return ordered;
    }
}

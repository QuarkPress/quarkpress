package org.quarkpress.security.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class UserProfileRepository implements PanacheRepositoryBase<UserProfileEntity, Long> {

    public Optional<UserProfileEntity> findBySubject(String subject) {
        return find("subject = ?1", subject).firstResultOptional();
    }

    public Optional<UserProfileEntity> findByNicename(String nicename) {
        return find("nicename = ?1", nicename).firstResultOptional();
    }

    public boolean nicenameTaken(String nicename) {
        return count("nicename = ?1", nicename) > 0;
    }
}

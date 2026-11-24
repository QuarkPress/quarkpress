package org.quarkword.security.deployment;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.quarkword.security.UserProfileCreated;

/** Records the profiles created while the import tests run. */
@ApplicationScoped
public class ImportEvents {

    final List<UserProfileCreated> seen = new CopyOnWriteArrayList<>();

    void on(@Observes UserProfileCreated event) {
        seen.add(event);
    }

    /** The events whose profile has {@code subject}. */
    public List<UserProfileCreated> forSubject(String subject) {
        return seen.stream().filter(e -> e.profile().subject().equals(subject)).toList();
    }
}

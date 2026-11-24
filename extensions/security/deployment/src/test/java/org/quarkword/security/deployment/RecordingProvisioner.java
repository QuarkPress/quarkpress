package org.quarkword.security.deployment;

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.quarkword.security.NewUser;
import org.quarkword.security.ProvisionedIdentity;
import org.quarkword.security.UserProvisioner;

/**
 * A stand-in identity provider for the import tests: each new username gets the subject {@code rec-<username>}
 * (or the one {@link #subjectFor} set), a username seen before is found again. It records every call and whether a
 * transaction was active during it, and can be made to fail.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class RecordingProvisioner implements UserProvisioner {

    /** A call: the user it was given and whether a transaction was active. */
    public record Call(NewUser user, boolean inTransaction) {}

    final List<Call> calls = new CopyOnWriteArrayList<>();

    final Map<String, String> known = new ConcurrentHashMap<>();

    final Map<String, String> subjects = new ConcurrentHashMap<>();

    volatile Supplier<RuntimeException> failure;

    @Override
    public ProvisionedIdentity provision(NewUser user) {
        calls.add(new Call(user, QuarkusTransaction.isActive()));
        Supplier<RuntimeException> fail = failure;
        if (fail != null) {
            throw fail.get();
        }
        String existing = known.get(user.username());
        if (existing != null) {
            return new ProvisionedIdentity(existing, false);
        }
        String subject = subjects.getOrDefault(user.username(), subjectOf(user.username()));
        known.put(user.username(), subject);
        return new ProvisionedIdentity(subject, true);
    }

    /** The subject a new user of {@code username} gets unless {@link #subjectFor} set another. */
    public static String subjectOf(String username) {
        return "rec-" + username;
    }

    /** Gives a new user of {@code username} the subject {@code subject}. */
    public void subjectFor(String username, String subject) {
        subjects.put(username, subject);
    }

    /** Makes {@code username} a user the identity provider already has, with {@code subject}. */
    public void existing(String username, String subject) {
        known.put(username, subject);
    }

    /** Makes every call throw what {@code what} makes, until {@link #stopFailing}. */
    public void failWith(Supplier<RuntimeException> what) {
        failure = what;
    }

    public void stopFailing() {
        failure = null;
    }

    /** The calls for {@code username}, in order. */
    public List<Call> callsFor(String username) {
        return calls.stream().filter(c -> c.user().username().equals(username)).toList();
    }
}

package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfileCreated;

/**
 * Finds or creates a profile in a transaction of its own (committed whatever the caller's transaction does): the work
 * of {@link DefaultUserProfileProvisioner}, also used by {@link SuperAdminBootstrap}, which must not record a login. A
 * new profile is named as WordPress names a new user: nicename from the username with numeric suffixes when taken, or
 * {@code user-<id>} when the username gives none; display name from the given and family names; e-mail from the
 * claims. For a login, the login time is written on creation and refreshed when it is older than
 * {@link #LOGIN_REFRESH}, and an e-mail the claims carry is written whenever it differs from the stored one (claims
 * without one leave the stored e-mail); otherwise the profile is left as it is (null login time for a new profile).
 * A login that writes locks the profile row and reads it again first, so it never overwrites what a concurrent change
 * of the row (such as a user meta write) committed.
 *
 * <p>A profile created for {@link SuperAdminBootstrap} ({@link #provisionBootstrap}) has only its subject to be named
 * by, so it is marked ({@link #BOOTSTRAP_MARK} in its meta). Its first login names it from the token's claims as a new
 * profile is named and removes the mark; no other profile is ever renamed.
 *
 * <p>A profile is inserted only if neither its subject nor its nicename exists, so the usual races (one subject logging
 * in from several requests, colliding nicenames) never fail. The one race left, another profile taking a fallback
 * nicename between the check and the update, fails that transaction; the attempt is retried, at most
 * {@value #ATTEMPTS} times in all.
 */
@ApplicationScoped
public class ProfileProvisioning {

    /** How old the stored login time must be before a login writes it again. */
    public static final Duration LOGIN_REFRESH = Duration.ofMinutes(15);

    static final int ATTEMPTS = 3;

    /** The meta key that marks a profile the bootstrap created and nobody has logged in with yet. */
    public static final String BOOTSTRAP_MARK = "quarkpress_bootstrap_profile";

    @Inject
    UserProfileRepository profiles;

    @Inject
    ProfileInserts inserts;

    @Inject
    QuarkPressClock clock;

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<UserProfileCreated> created;

    /**
     * Finds or creates the subject's profile; {@code login} says whether this is a login, which writes the login time.
     * Do not call it inside a transaction that already holds the subject's or nicename's profile row (inserted or
     * locked): the transaction of its own waits for that row and can deadlock.
     */
    @ActivateRequestContext
    public UserProfile provision(ProfileClaims claims, boolean login) {
        return provision(claims, login, false);
    }

    /**
     * Finds or creates the subject's profile for {@link SuperAdminBootstrap}, recording no login; a profile created here
     * is marked, so its first login names it from the token's claims.
     */
    @ActivateRequestContext
    public UserProfile provisionBootstrap(ProfileClaims claims) {
        return provision(claims, false, true);
    }

    private UserProfile provision(ProfileClaims claims, boolean login, boolean bootstrap) {
        List<RuntimeException> failures = new ArrayList<>();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                return QuarkusTransaction.requiringNew().call(() -> provisionOnce(claims, login, bootstrap));
            } catch (RuntimeException failed) {
                if (!isRace(failed)) {
                    throw failed;
                }
                failures.add(failed);
            }
        }
        RuntimeException last = failures.remove(failures.size() - 1);
        failures.forEach(last::addSuppressed);
        throw last;
    }

    /** A unique-key violation, a deadlock or a lock timeout, on PostgreSQL or MySQL, however it is wrapped. */
    static boolean isRace(Throwable failure) {
        if (LockConflicts.isDeadlockOrLockTimeout(failure)) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("23505".equals(sql.getSQLState()) || sql.getErrorCode() == 1062)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private UserProfile provisionOnce(ProfileClaims claims, boolean login, boolean bootstrap) {
        Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
        Optional<UserProfileEntity> existing = profiles.findBySubject(claims.subject());
        if (existing.isPresent()) {
            return login ? touched(existing.get(), claims, now) : existing.get().toProfile();
        }
        String displayName = UserNames.displayName(
                claims.givenName(), claims.familyName(), claims.preferredUsername(), claims.subject());
        Optional<String> base =
                UserNames.nicenameBase(claims.preferredUsername().orElse(""));
        if (base.isPresent()) {
            for (int suffix = 1; ; suffix++) {
                String candidate = suffix == 1 ? base.get() : UserNames.withSuffix(base.get(), suffix);
                if (profiles.nicenameTaken(candidate)) {
                    continue;
                }
                Optional<Inserted> outcome = insert(claims, displayName, candidate, now, login);
                if (outcome.isPresent()) {
                    if (outcome.get().ours()) {
                        if (bootstrap) {
                            mark(profiles.findById(outcome.get().profile().id().value()));
                        }
                        transactions.publish(
                                created,
                                new UserProfileCreated(now, outcome.get().profile()));
                    }
                    return outcome.get().profile();
                }
                // nothing inserted and the subject has no profile: the candidate was taken meanwhile
            }
        }
        String placeholder = "~" + UUID.randomUUID().toString().replace("-", "");
        Inserted outcome = insert(claims, displayName, placeholder, now, login)
                .orElseThrow(() -> new IllegalStateException(
                        "a profile with the unique placeholder nicename " + placeholder + " exists"));
        if (!outcome.ours()) {
            return outcome.profile();
        }
        UserProfileEntity entity = profiles.findById(outcome.profile().id().value());
        String fallback = UserNames.fallbackNicename(UserId.of(entity.id));
        String candidate = fallback;
        for (int suffix = 2; profiles.nicenameTaken(candidate); suffix++) {
            candidate = UserNames.withSuffix(fallback, suffix);
        }
        entity.nicename = candidate;
        if (bootstrap) {
            mark(entity);
        }
        profiles.flush();
        UserProfile profile = entity.toProfile();
        transactions.publish(created, new UserProfileCreated(now, profile));
        return profile;
    }

    /** A profile this transaction inserted ({@code ours}), or the one a concurrent login of the same subject made. */
    private record Inserted(UserProfile profile, boolean ours) {}

    /**
     * Inserts a profile. Returns it when inserted, the profile a concurrent login of the same subject created (read
     * with a shared lock, so a profile committed after this transaction's snapshot is seen), or empty when the
     * nicename was taken.
     */
    private Optional<Inserted> insert(
            ProfileClaims claims, String displayName, String nicename, Instant now, boolean login) {
        String subject = claims.subject();
        Optional<Long> id = inserts.insertIfAbsent(subject, displayName, nicename, now, login, claims.email());
        if (id.isPresent()) {
            return Optional.of(new Inserted(profiles.findById(id.get()).toProfile(), true));
        }
        return inserts.lockedIdOfSubject(subject)
                .map(found -> new Inserted(
                        profiles.find("id = ?1", found)
                                .withLock(LockModeType.PESSIMISTIC_READ)
                                .singleResult()
                                .toProfile(),
                        false));
    }

    /**
     * A login of an existing profile: when the login time is due, the e-mail changed or the profile carries the
     * bootstrap's mark, the row is locked and read again, and what is still due is written.
     */
    private UserProfile touched(UserProfileEntity entity, ProfileClaims claims, Instant now) {
        Optional<String> email = claims.email();
        if (!loginChanges(entity, email, now) && !markedByBootstrap(entity)) {
            return entity.toProfile();
        }
        ProfileRowLock.lock(profiles, entity, "login: provision it in a separate transaction");
        if (markedByBootstrap(entity)) {
            name(entity, claims);
        }
        if (loginDue(entity, now)) {
            entity.lastLoginAt = now;
        }
        if (emailChanged(entity, email)) {
            entity.email = email.get();
        }
        return entity.toProfile();
    }

    /** Whether the profile carries the bootstrap's mark: created for the bootstrap, never logged in with since. */
    static boolean markedByBootstrap(UserProfileEntity entity) {
        return entity.meta instanceof ObjectNode meta
                && meta.path(BOOTSTRAP_MARK).asBoolean(false);
    }

    private static void mark(UserProfileEntity entity) {
        ObjectNode meta =
                entity.meta instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        meta.put(BOOTSTRAP_MARK, true);
        entity.meta = meta;
    }

    /**
     * Names a marked bootstrap profile at its first login, and removes the mark, as a new profile is named: display name from the claims, nicename
     * from the username with numeric suffixes when taken; a username that gives no nicename keeps the fallback one.
     */
    private void name(UserProfileEntity entity, ProfileClaims claims) {
        ObjectNode meta = ((ObjectNode) entity.meta).deepCopy();
        meta.remove(BOOTSTRAP_MARK);
        entity.meta = meta;
        entity.displayName = UserNames.displayName(
                claims.givenName(), claims.familyName(), claims.preferredUsername(), claims.subject());
        Optional<String> base =
                UserNames.nicenameBase(claims.preferredUsername().orElse(""));
        if (base.isEmpty()) {
            return;
        }
        for (int suffix = 1; ; suffix++) {
            String candidate = suffix == 1 ? base.get() : UserNames.withSuffix(base.get(), suffix);
            if (candidate.equals(entity.nicename) || !profiles.nicenameTaken(candidate)) {
                entity.nicename = candidate;
                // a nicename another profile takes meanwhile fails the flush, and the login is tried again
                profiles.flush();
                return;
            }
        }
    }

    private static boolean loginChanges(UserProfileEntity entity, Optional<String> email, Instant now) {
        return loginDue(entity, now) || emailChanged(entity, email);
    }

    private static boolean loginDue(UserProfileEntity entity, Instant now) {
        return entity.lastLoginAt == null
                || entity.lastLoginAt.plus(LOGIN_REFRESH).isBefore(now);
    }

    private static boolean emailChanged(UserProfileEntity entity, Optional<String> email) {
        return email.isPresent() && !email.get().equals(entity.email);
    }
}

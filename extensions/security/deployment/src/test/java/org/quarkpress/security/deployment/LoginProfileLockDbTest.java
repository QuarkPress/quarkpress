package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.BeforeDestroyed;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionScoped;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.ProfileProvisioning;

/**
 * A login that writes to an existing profile locks the profile's row as every other profile write does: it refuses at
 * once when a transaction that waits for it holds the row, and it records the lock so that a scope its own transaction
 * spawns refuses in turn.
 */
class LoginProfileLockDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(LoginProfileLockDbTest.class, TransactionEnds.class);

    /**
     * While armed for a thread, asks at the end of each of that thread's transactions (before it completes) whether a
     * scope spawned there sees the watched lock as held by its spawner. The provisioning's own transaction is the only
     * one on that thread while armed.
     */
    @ApplicationScoped
    public static class TransactionEnds {

        @Inject
        SiteScope scope;

        final List<Object> answers = new CopyOnWriteArrayList<>();
        volatile Thread armed;
        volatile String lock;
        volatile SiteId other;

        void beforeEnd(@Observes @BeforeDestroyed(TransactionScoped.class) Object transaction) {
            if (Thread.currentThread() != armed) {
                return;
            }
            String name = lock;
            try {
                answers.add(scope.call(other, () -> HeldLocks.heldBySpawner(name)));
            } catch (Exception e) {
                answers.add(e);
            }
        }

        void arm(String name, SiteId site) {
            answers.clear();
            lock = name;
            other = site;
            armed = Thread.currentThread();
        }

        void disarm() {
            armed = null;
        }

        /**
         * What the scopes spawned since {@link #arm} answered, or the exception they failed with (read through a
         * method: the test holds a client proxy, whose fields are not the bean's).
         */
        List<Object> answers() {
            return List.copyOf(answers);
        }
    }

    @Inject
    ProfileProvisioning provisioning;

    @Inject
    UserProfiles profiles;

    @Inject
    SiteScope scope;

    @Inject
    TransactionEnds ends;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private static ProfileClaims claims(String subject, String email) {
        return new ProfileClaims(subject, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(email));
    }

    @Test
    void aLoginWhoseProfileASpawningTransactionHoldsIsRefusedAtOnce() throws Exception {
        String subject = "sub-held-" + UUID.randomUUID();
        UserProfile profile = provisioning.provision(claims(subject, "a@quarkpress.test"), true);
        SiteId first = TestData.site(dataSource);
        SiteId second = TestData.site(dataSource);
        long started = System.nanoTime();
        assertThatThrownBy(() -> scope.call(first, () -> {
                    HeldLocks.record("qp_user_profile:" + profile.id().value());
                    return scope.call(second, () -> provisioning.provision(claims(subject, "b@quarkpress.test"), true));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the profile of user " + profile.id().value()
                        + " is locked by a transaction that waits for this login: provision it in a separate"
                        + " transaction");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(profiles.find(profile.id()).orElseThrow().email()).contains("a@quarkpress.test");
    }

    @Test
    void aLoginThatLocksTheProfileRecordsTheLockForScopesItsTransactionSpawns() {
        String subject = "sub-record-" + UUID.randomUUID();
        UserProfile profile = provisioning.provision(claims(subject, "a@quarkpress.test"), true);
        SiteId other = TestData.site(dataSource);
        ends.arm("qp_user_profile:" + profile.id().value(), other);
        try {
            // a changed e-mail makes the login lock the row
            assertThat(provisioning
                            .provision(claims(subject, "b@quarkpress.test"), true)
                            .email())
                    .contains("b@quarkpress.test");
        } finally {
            ends.disarm();
        }
        assertThat(ends.answers()).containsExactly(true);
    }

    @Test
    void aLoginThatChangesNothingTakesNoLock() {
        String subject = "sub-quiet-" + UUID.randomUUID();
        UserProfile profile = provisioning.provision(claims(subject, "a@quarkpress.test"), true);
        SiteId other = TestData.site(dataSource);
        ends.arm("qp_user_profile:" + profile.id().value(), other);
        try {
            provisioning.provision(claims(subject, "a@quarkpress.test"), true);
        } finally {
            ends.disarm();
        }
        // the transaction ended, and the scope it spawned saw no lock: the observer runs, so the test above is sound
        assertThat(ends.answers()).containsExactly(false);
    }
}

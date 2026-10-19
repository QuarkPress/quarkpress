package org.quarkpress.security.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.HeldLocks;
import org.quarkpress.persistence.runtime.NetworkLockRepository;
import org.quarkpress.persistence.runtime.OptionTransactions;
import org.quarkpress.security.SuperAdminChanged;

/**
 * Network administrator rows, written without a capability check (callers check first). Each write takes the network
 * lock as its first statement, like every write of network data, so writes of these rows apply one after the other;
 * the rows are then read with a locking read, which sees every committed write even when the caller's transaction
 * started earlier (MySQL's REPEATABLE READ snapshot). A failure marks the transaction rollback-only; a deadlock or a
 * lock timeout surfaces as a {@link jakarta.persistence.PessimisticLockException}: retry the whole transaction.
 *
 * <p>A write joins the caller's transaction. It refuses to start on a scope thread whose spawner holds the network
 * lock ({@link NetworkLockRepository#HELD_MESSAGE}).
 */
@ApplicationScoped
public class NetworkAdmins {

    @Inject
    NetworkAdminRepository admins;

    @Inject
    UserProfileRepository profiles;

    @Inject
    NetworkLockRepository lock;

    @Inject
    QuarkPressClock clock;

    @Inject
    OptionTransactions transactions;

    @Inject
    Event<SuperAdminChanged> changed;

    /**
     * Makes {@code user} a network administrator, recording who granted it (empty for the system and the bootstrap).
     * Returns whether the user became one: false for an unknown user or one who already is. Fires
     * {@link SuperAdminChanged}: synchronous observers inside the transaction, asynchronous ones after it commits.
     */
    @ActivateRequestContext
    @Transactional
    public boolean grant(UserId user, Optional<UserId> grantedBy) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(grantedBy, "grantedBy");
        lock.lock();
        try {
            String profileLock = JpaMemberships.profileLock(user);
            if (HeldLocks.heldBySpawner(profileLock)) {
                throw new IllegalStateException("the profile of user " + user.value()
                        + " is locked by a transaction that waits for this grant: grant in a separate transaction");
            }
            // the profile is read with a shared lock (network lock, then profile, then the admin row): it cannot go
            // away before the insert, which refers to it
            HeldLocks.record(profileLock);
            if (profiles.findByIdOptional(user.value(), LockModeType.PESSIMISTIC_READ)
                            .isEmpty()
                    || current(user).isPresent()) {
                return false;
            }
            NetworkAdminEntity row = new NetworkAdminEntity();
            row.userId = user.value();
            row.grantedAt = now();
            row.grantedBy = grantedBy.map(UserId::value).orElse(null);
            admins.persist(row);
            admins.flush();
            transactions.publish(changed, new SuperAdminChanged(row.grantedAt, user, true));
            return true;
        } catch (RuntimeException failure) {
            throw lock.failWrite(
                    LockConflicts.retryable(failure, "a grant of network administration to user " + user.value()));
        }
    }

    /**
     * Ends {@code user}'s network administration. Returns whether the user stopped being one. Revoking the last one is
     * allowed, as in WordPress. Fires {@link SuperAdminChanged}: synchronous observers inside the transaction,
     * asynchronous ones after it commits.
     */
    @ActivateRequestContext
    @Transactional
    public boolean revoke(UserId user) {
        Objects.requireNonNull(user, "user");
        lock.lock();
        try {
            Optional<NetworkAdminEntity> row = current(user);
            if (row.isEmpty()) {
                return false;
            }
            admins.delete(row.get());
            admins.flush();
            transactions.publish(changed, new SuperAdminChanged(now(), user, false));
            return true;
        } catch (RuntimeException failure) {
            throw lock.failWrite(
                    LockConflicts.retryable(failure, "a revocation of network administration of user " + user.value()));
        }
    }

    /** The user's row, read with a lock (the latest committed state, not the transaction's snapshot). */
    private Optional<NetworkAdminEntity> current(UserId user) {
        return admins.find("userId = ?1", user.value())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .firstResultOptional();
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }
}

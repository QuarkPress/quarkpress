package org.quarkpress.security.runtime;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.HeldLocks;

/**
 * How a transaction locks a user's profile row for update: refused at once when a transaction that waits for this work
 * holds the row (the two would wait for each other until the database's lock timeout), then locked with the row's
 * state read by the locking query (on MySQL not the transaction's older snapshot), then recorded in {@link HeldLocks}
 * so that scope threads this transaction spawns refuse in turn.
 */
final class ProfileRowLock {

    private ProfileRowLock() {}

    /** The name under which a transaction records its lock on the user's profile row. */
    static String name(UserId user) {
        return "qp_user_profile:" + user.value();
    }

    /**
     * Refuses with {@link IllegalStateException} when a transaction that waits for this work holds the user's profile
     * row; {@code work} completes the message "... locked by a transaction that waits for this ".
     */
    static void refuseIfHeldBySpawner(UserId user, String work) {
        if (HeldLocks.heldBySpawner(name(user))) {
            throw new IllegalStateException(
                    "the profile of user " + user.value() + " is locked by a transaction that waits for this " + work);
        }
    }

    /**
     * The user's profile, locked for update until the transaction ends and read by the locking query; empty when it
     * does not exist. Pending changes are flushed first, and a profile this transaction already loaded is detached, as
     * a refresh would read without the lock.
     */
    static Optional<UserProfileEntity> lock(UserProfileRepository profiles, UserId user, String work) {
        refuseIfHeldBySpawner(user, work);
        UserProfileEntity loaded = profiles.findById(user.value());
        if (loaded == null) {
            return Optional.empty();
        }
        profiles.flush();
        profiles.getEntityManager().detach(loaded);
        Optional<UserProfileEntity> locked = profiles.find("id = ?1", user.value())
                .withLock(LockModeType.PESSIMISTIC_WRITE)
                .singleResultOptional();
        HeldLocks.record(name(user));
        return locked;
    }

    /** Locks the row of a profile this transaction loaded, reading its state again under the lock. */
    static void lock(UserProfileRepository profiles, UserProfileEntity loaded, String work) {
        UserId user = UserId.of(loaded.id);
        refuseIfHeldBySpawner(user, work);
        profiles.getEntityManager().refresh(loaded, LockModeType.PESSIMISTIC_WRITE);
        HeldLocks.record(name(user));
    }
}

package org.quarkpress.persistence.runtime;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Row locks a transaction holds that a scope thread it spawns must not wait for. A scope for another binding runs on
 * a thread of its own, in a transaction of its own, while the spawning thread waits for it with its own transaction
 * open; if the scope's work then waits for a row lock the spawner's transaction holds, the two wait for each other and
 * no database sees a deadlock. Code that takes such a lock records it under a name of its choosing; scope threads
 * inherit the names their spawners' transactions recorded (at any depth), so that the work can refuse at once instead
 * of waiting until a timeout.
 */
public final class HeldLocks {

    private static final String RESOURCE = HeldLocks.class.getName();

    /** On a scope thread: the locks the transactions up its spawn chain recorded when it was spawned. */
    private static final ThreadLocal<Set<String>> SPAWNER_LOCKS = new ThreadLocal<>();

    private HeldLocks() {}

    /**
     * Records that the current transaction holds the lock named {@code name} until it ends; refused with
     * {@link IllegalStateException} when no transaction is active.
     */
    public static void record(String name) {
        Objects.requireNonNull(name, "name");
        TransactionSynchronizationRegistry registry = registry();
        int status = registry.getTransactionStatus();
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            throw new IllegalStateException("a held lock is recorded for a transaction, and none is active");
        }
        @SuppressWarnings("unchecked")
        Set<String> names = (Set<String>) registry.getResource(RESOURCE);
        if (names == null) {
            names = ConcurrentHashMap.newKeySet();
            registry.putResource(RESOURCE, names);
        }
        names.add(name);
    }

    /**
     * Whether this is a scope thread and a transaction up its spawn chain recorded {@code name}: that transaction
     * ends only after this thread's work, so waiting here for the lock would never end.
     */
    public static boolean heldBySpawner(String name) {
        Set<String> names = SPAWNER_LOCKS.get();
        return names != null && names.contains(name);
    }

    /**
     * Read on the spawning thread: the names a new scope thread inherits, those this thread inherited and those its
     * transaction recorded. The transaction's names are passed only while a request context is active on this
     * thread, as it is wherever a site or the network is bound; a transaction on a thread without one passes none.
     */
    static Set<String> forScopeThread() {
        Set<String> names = new HashSet<>();
        Set<String> carried = SPAWNER_LOCKS.get();
        if (carried != null) {
            names.addAll(carried);
        }
        if (Arc.container().requestContext().isActive() && QuarkusTransaction.isActive()) {
            try {
                @SuppressWarnings("unchecked")
                Set<String> recorded = (Set<String>) registry().getResource(RESOURCE);
                if (recorded != null) {
                    names.addAll(recorded);
                }
            } catch (IllegalStateException noTransaction) {
                // nothing recorded without a transaction
            }
        }
        return Set.copyOf(names);
    }

    /** On a new scope thread, before its work. */
    static void inherit(Set<String> names) {
        if (!names.isEmpty()) {
            SPAWNER_LOCKS.set(names);
        }
    }

    /** On a scope thread, after its work. */
    static void forget() {
        SPAWNER_LOCKS.remove();
    }

    private static TransactionSynchronizationRegistry registry() {
        return Arc.container()
                .instance(TransactionSynchronizationRegistry.class)
                .get();
    }
}

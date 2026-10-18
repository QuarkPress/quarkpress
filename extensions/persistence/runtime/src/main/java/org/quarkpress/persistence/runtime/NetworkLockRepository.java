package org.quarkpress.persistence.runtime;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.sql.Statement;
import org.hibernate.Session;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.jboss.logging.Logger;

@ApplicationScoped
public class NetworkLockRepository implements PanacheRepositoryBase<NetworkLockEntity, Short> {

    /** The refusal of a network write that would wait for a lock its caller holds. */
    public static final String HELD_MESSAGE = "a network write cannot start inside a transaction that already holds"
            + " the network lock; call it outside that transaction";

    /** How long a network transaction waits for any lock, in seconds, before it fails. */
    public static final int LOCK_TIMEOUT_SECONDS = 10;

    private static final String HELD = "org.quarkpress.persistence.runtime.NetworkLockRepository.held";

    private static final String POSTGRESQL_TIMEOUT = "set local lock_timeout = '" + LOCK_TIMEOUT_SECONDS + "s'";
    private static final String MYSQL_TIMEOUT = "set session innodb_lock_wait_timeout = " + LOCK_TIMEOUT_SECONDS;
    private static final String MYSQL_RESTORE = "set session innodb_lock_wait_timeout = default";

    private static final Logger LOG = Logger.getLogger(NetworkLockRepository.class);

    @Inject
    TransactionSynchronizationRegistry registry;

    private volatile Boolean postgresql;

    /**
     * Whether the current transaction has taken the network lock, or this is a scope thread (at any depth) spawned by
     * one that did. Only a transaction that is active or marked for rollback counts: a completing one has released
     * its lock. A network write that runs on a thread and in a transaction of its own would wait for that lock while
     * the holder waits for it, which no database can see.
     */
    public boolean heldByCurrentTransaction() {
        if (Scopes.spawnerHoldsNetworkLock()) {
            return true;
        }
        int status = registry.getTransactionStatus();
        if (status != Status.STATUS_ACTIVE && status != Status.STATUS_MARKED_ROLLBACK) {
            return false;
        }
        try {
            return registry.getResource(HELD) != null;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    /**
     * Locks the network lock row until the transaction ends. Every write of network data (sites, domain aliases,
     * network options) takes it first, so those writes run one after the other without locking rows that inserts of a
     * site's rows hold shared locks on (no foreign key refers to this row). Call it as the transaction's first
     * statement, so that later reads in the transaction see every write that committed before the lock was granted.
     *
     * <p>From here on the transaction waits at most {@value #LOCK_TIMEOUT_SECONDS} seconds for each lock it needs
     * (this one included), then fails with a lock timeout. Some waits are invisible to the database's deadlock
     * detection: a network write on a thread of its own waiting for rows locked by a transaction that waits for that
     * thread. Without a bound such a write would hold the network lock, and stall every network write, until the
     * other transaction ends (on PostgreSQL, whose default is to wait forever, possibly never). PostgreSQL sets
     * {@code lock_timeout} for the rest of the transaction. MySQL sets the session's {@code innodb_lock_wait_timeout}
     * and restores the server default before the transaction commits; after a rollback the pooled connection keeps the
     * shorter timeout until the next network write on it commits.
     */
    public void lock() {
        if (Scopes.spawnerHoldsNetworkLock()) {
            throw new IllegalStateException(HELD_MESSAGE);
        }
        boundLockWaits();
        if (findByIdOptional(NetworkLockEntity.ROW, LockModeType.PESSIMISTIC_WRITE)
                .isEmpty()) {
            throw new IllegalStateException("the qp_network_lock row is missing; QuarkPress's migrations create it");
        }
        registry.putResource(HELD, Boolean.TRUE);
    }

    /** Bounds this transaction's lock waits, once per transaction. */
    private void boundLockWaits() {
        if (registry.getResource(HELD) != null) {
            return;
        }
        Session session = getSession();
        if (postgresql(session)) {
            execute(session, POSTGRESQL_TIMEOUT);
            return;
        }
        execute(session, MYSQL_TIMEOUT);
        registry.registerInterposedSynchronization(new Synchronization() {
            @Override
            public void beforeCompletion() {
                try {
                    execute(session, MYSQL_RESTORE);
                } catch (RuntimeException failure) {
                    LOG.debugf(failure, "could not restore innodb_lock_wait_timeout after a network write");
                }
            }

            @Override
            public void afterCompletion(int status) {}
        });
    }

    private static void execute(Session session, String sql) {
        session.doWork(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
    }

    private boolean postgresql(Session session) {
        Boolean known = postgresql;
        if (known == null) {
            known = session.getSessionFactory()
                            .unwrap(SessionFactoryImplementor.class)
                            .getJdbcServices()
                            .getDialect()
                    instanceof PostgreSQLDialect;
            postgresql = known;
        }
        return known;
    }
}

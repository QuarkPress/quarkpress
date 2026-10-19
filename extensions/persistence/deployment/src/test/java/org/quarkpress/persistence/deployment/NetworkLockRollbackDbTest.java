package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.IntNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.runtime.NetworkLockRepository;
import org.quarkpress.scheduling.runtime.ScopeProbe;

class NetworkLockRollbackDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(NetworkLockRollbackDbTest.class, ScopeProbe.class);

    @Inject
    NetworkLockRepository lock;

    @Inject
    NetworkOptions options;

    @Inject
    ScopeProbe probe;

    @Inject
    TransactionSynchronizationRegistry registry;

    /** Whether the failure is a database lock timeout: PostgreSQL's SQLSTATE 55P03 or MySQL's error 1205. */
    private static boolean lockTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("55P03".equals(sql.getSQLState()) || sql.getErrorCode() == 1205)) {
                return true;
            }
        }
        return false;
    }

    /** Holds the network lock in a transaction of its own until {@code release} opens. */
    private Thread holdLock(CountDownLatch held, CountDownLatch release) {
        return Thread.ofVirtual()
                .start(() ->
                        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
                            lock.lock();
                            held.countDown();
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                        })));
    }

    @Test
    void aLockTimeoutCaughtInsideANetworkTransactionLeavesItRollbackOnly() throws Exception {
        // Guards the change on MySQL only: on PostgreSQL Hibernate already marks the transaction on a lock timeout.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdLock(held, release);
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(-1);
        try {
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            // The work catches the timeout and carries on, as a careless network-bound caller would; its transaction
            // must not commit.
            assertThatThrownBy(() -> probe.inNetwork(() -> {
                        try {
                            lock.lock();
                        } catch (RuntimeException timeout) {
                            caught.set(timeout);
                        }
                        status.set(registry.getTransactionStatus());
                        return null;
                    }))
                    .isInstanceOf(QuarkusTransactionException.class)
                    .hasCauseInstanceOf(RollbackException.class);
        } finally {
            release.countDown();
            holder.join();
        }
        assertThat(lockTimeout(caught.get()))
                .as("a lock timeout: %s", caught.get())
                .isTrue();
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
    }

    @Test
    void aNetworkOptionWriteThatTimesOutInsideANetworkTransactionLeavesItRollbackOnly() throws Exception {
        // Pins existing behaviour: the @Transactional interceptor of NetworkOptions marks the joined transaction.
        String name = "c14-" + UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdLock(held, release);
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        AtomicInteger status = new AtomicInteger(-1);
        try {
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> probe.inNetwork(() -> {
                        try {
                            options.set(name, IntNode.valueOf(1));
                        } catch (RuntimeException timeout) {
                            caught.set(timeout);
                        }
                        status.set(registry.getTransactionStatus());
                        return null;
                    }))
                    .isInstanceOf(QuarkusTransactionException.class)
                    .hasCauseInstanceOf(RollbackException.class);
        } finally {
            release.countDown();
            holder.join();
        }
        assertThat(lockTimeout(caught.get()))
                .as("a lock timeout: %s", caught.get())
                .isTrue();
        assertThat(status.get()).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
        assertThat(Requests.call(() -> options.find(name))).isEmpty();
    }

    @Test
    void failWriteMarksTheTransactionAndReturnsTheFailure() {
        IllegalStateException failure = new IllegalStateException("boom");
        int[] seen = new int[1];
        // The mark is the registry's, so QuarkusTransaction's own commit refuses; the transaction rolls back.
        assertThatThrownBy(() ->
                        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
                            assertThat(lock.failWrite(failure)).isSameAs(failure);
                            seen[0] = registry.getTransactionStatus();
                        })))
                .isInstanceOf(QuarkusTransactionException.class)
                .hasCauseInstanceOf(RollbackException.class);
        assertThat(seen[0]).isEqualTo(Status.STATUS_MARKED_ROLLBACK);
    }

    @Test
    void failWriteOutsideATransactionKeepsTheFailure() {
        IllegalStateException failure = new IllegalStateException("boom");
        assertThat(Requests.call(() -> lock.failWrite(failure))).isSameAs(failure);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(IllegalStateException.class);
    }
}

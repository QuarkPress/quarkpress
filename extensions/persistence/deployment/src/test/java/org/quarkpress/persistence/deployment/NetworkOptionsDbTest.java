package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.node.IntNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.NetworkOptionChanged;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.NetworkLockRepository;

class NetworkOptionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(NetworkOptionsDbTest.class, Changes.class)
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("alpha", "test", false, "Alpha"),
                    new SiteFeatureBuildItem("beta", "test", false, "Beta")));

    static final int WRITERS = 8;

    @ApplicationScoped
    public static class Changes {
        final List<NetworkOptionChanged> seen = new CopyOnWriteArrayList<>();

        void on(@Observes NetworkOptionChanged event) {
            seen.add(event);
        }

        // Read through a method: a field of the injected client proxy is not the bean's field.
        List<NetworkOptionChanged> seen() {
            return seen;
        }
    }

    @Inject
    FeatureToggles toggles;

    @Inject
    NetworkOptions options;

    @Inject
    Changes changes;

    @Inject
    SiteScope scope;

    @Inject
    TestSites testSites;

    @Test
    void aNestedScopeOfATransactionHoldingTheNetworkLockRefusesToo() {
        SiteId first = testSites.create("nest-a");
        SiteId second = testSites.create("nest-b");
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> assertThatThrownBy(() -> QuarkusTransaction.requiringNew()
                                .run(() -> {
                                    options.set("held-" + UUID.randomUUID(), IntNode.valueOf(1));
                                    try {
                                        scope.call(
                                                first,
                                                () -> scope.call(
                                                        second,
                                                        () -> options.set(
                                                                "deep-" + UUID.randomUUID(), IntNode.valueOf(2))));
                                    } catch (RuntimeException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(NetworkLockRepository.HELD_MESSAGE)));
    }

    @Test
    void aScopeSpawnedByATransactionHoldingTheNetworkLockRefusesInsteadOfWaiting() {
        String held = "held-" + UUID.randomUUID();
        String nested = "nested-" + UUID.randomUUID();
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> assertThatThrownBy(() -> QuarkusTransaction.requiringNew()
                                .run(() -> {
                                    options.set(held, IntNode.valueOf(1));
                                    try {
                                        scope.call(SiteId.of(1), () -> options.set(nested, IntNode.valueOf(2)));
                                    } catch (RuntimeException e) {
                                        throw e;
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage(NetworkLockRepository.HELD_MESSAGE)));
        // the refused transaction rolled back and released the lock; a scope without a lock holder still writes
        assertThat(Requests.call(() -> options.find(held))).isEmpty();
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> {
                    try {
                        scope.call(SiteId.of(1), () -> options.set(nested, IntNode.valueOf(2)));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
        assertThat(Requests.call(() -> options.find(nested))).isPresent();
    }

    @Test
    void concurrentFirstSetsOfOneNameAllSucceed() throws Exception {
        String name = "race-" + UUID.randomUUID();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            int value = i;
            tasks.add(() -> Requests.call(() -> options.set(name, IntNode.valueOf(value))));
        }
        assertThat(Races.run(tasks)).containsOnly(Boolean.TRUE);
        assertThat(changes.seen())
                .filteredOn(event -> event.name().equals(name))
                .hasSize(WRITERS);
        assertThat(changes.seen())
                .filteredOn(event -> event.name().equals(name) && event.before().isMissingNode())
                .hasSize(1);
        assertThat(Requests.call(() -> options.find(name)))
                .hasValueSatisfying(value -> assertThat(value.intValue()).isBetween(0, WRITERS - 1));
    }

    @Test
    void aWriteAfterAnEarlierReadSeesTheChangeCommittedInBetween() throws Exception {
        String name = "stale-" + UUID.randomUUID();
        Requests.run(() -> options.set(name, IntNode.valueOf(0)));
        // The value committed in between is 1, so setting 1 changes nothing.
        Object changed = writeAfterReadAndAChangeByAnother(name, () -> options.set(name, IntNode.valueOf(1)));
        assertThat(changed).isEqualTo(false);
        assertThat(changes.seen())
                .filteredOn(event -> event.name().equals(name))
                .hasSize(2);
    }

    @Test
    void aDeleteAfterAnEarlierReadReportsTheChangeCommittedInBetween() throws Exception {
        String name = "stale-del-" + UUID.randomUUID();
        Requests.run(() -> options.set(name, IntNode.valueOf(0)));
        Object existed = writeAfterReadAndAChangeByAnother(name, () -> options.delete(name));
        assertThat(existed).isEqualTo(true);
        List<NetworkOptionChanged> deletions = changes.seen().stream()
                .filter(event -> event.name().equals(name) && event.after().isMissingNode())
                .toList();
        assertThat(deletions).hasSize(1);
        assertThat(deletions.get(0).before()).isEqualTo(IntNode.valueOf(1));
    }

    /**
     * One transaction reads the option, another sets it to 1 and commits, then the first runs {@code write}; returns
     * what {@code write} returned.
     */
    private Object writeAfterReadAndAChangeByAnother(String name, Callable<Object> write) throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> first = pool.submit(() ->
                    Requests.call(() -> QuarkusTransaction.requiringNew().call(() -> {
                        options.find(name);
                        read.countDown();
                        await(changed);
                        return write.call();
                    })));
            await(read);
            Requests.run(() -> options.set(name, IntNode.valueOf(1)));
            changed.countDown();
            return first.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoNetworkDefaultTogglesInOneTransactionBothStick() {
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
            toggles.setNetworkDefault("alpha", true);
            toggles.setNetworkDefault("beta", true);
        }));
        assertThat(FeatureOptions.switches(Requests.call(() -> options.find(FeatureOptions.NETWORK))))
                .containsEntry("alpha", true)
                .containsEntry("beta", true);
    }

    @Test
    void aNewNameSetTwiceInOneTransactionKeepsTheSecondValue() {
        String name = "twice-" + UUID.randomUUID();
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
            assertThat(options.set(name, IntNode.valueOf(1))).isTrue();
            assertThat(options.set(name, IntNode.valueOf(2))).isTrue();
        }));
        assertThat(Requests.call(() -> options.find(name))).contains(IntNode.valueOf(2));
    }

    @Test
    void aNewNameSetThenDeletedInOneTransactionLeavesNothing() {
        String name = "gone-" + UUID.randomUUID();
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
            assertThat(options.set(name, IntNode.valueOf(1))).isTrue();
            assertThat(options.delete(name)).isTrue();
        }));
        assertThat(Requests.call(() -> options.find(name))).isEmpty();
    }

    /** Whether the failure is a database lock timeout: PostgreSQL's SQLSTATE 55P03 or MySQL's error 1205. */
    private static boolean lockTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("55P03".equals(sql.getSQLState()) || sql.getErrorCode() == 1205)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aNetworkWriteWaitsForTheNetworkLockAtMostTenSeconds() throws Exception {
        String held = "held-" + UUID.randomUUID();
        String waiting = "waiting-" + UUID.randomUUID();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong failedAfter = new AtomicLong();
        List<Callable<Object>> tasks = List.of(
                () -> {
                    Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
                        options.set(held, IntNode.valueOf(1));
                        locked.countDown();
                        try {
                            // longer than the lock timeout; released early once the other write has failed
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                    return "committed";
                },
                () -> {
                    await(locked);
                    long start = System.nanoTime();
                    try {
                        return Requests.call(() -> options.set(waiting, IntNode.valueOf(2)));
                    } finally {
                        failedAfter.set(System.nanoTime() - start);
                        release.countDown();
                    }
                });
        List<Object> results = Races.run(tasks);
        assertThat(results.get(0)).isEqualTo("committed");
        assertThat(results.get(1))
                .isInstanceOfSatisfying(
                        RuntimeException.class,
                        failure -> assertThat(lockTimeout(failure))
                                .as("a lock timeout: %s", failure)
                                .isTrue());
        assertThat(Duration.ofNanos(failedAfter.get())).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(19));
        assertThat(Requests.call(() -> options.find(held))).contains(IntNode.valueOf(1));
        assertThat(Requests.call(() -> options.find(waiting))).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

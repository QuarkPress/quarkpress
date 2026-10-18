package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.IntNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.NetworkOptionChanged;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.runtime.FeatureOptions;

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

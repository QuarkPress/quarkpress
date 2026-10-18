package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.FeatureOptions;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.QuarkPressJsonFormatMapper;

class OptionConcurrencyDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(OptionConcurrencyDbTest.class, Changes.class)
            .addBuildChainCustomizer(chain -> TestFeatures.declare(
                    chain,
                    new SiteFeatureBuildItem("alpha", "test", false, "Alpha"),
                    new SiteFeatureBuildItem("beta", "test", false, "Beta")));

    static final int WRITERS = 8;

    @ApplicationScoped
    public static class Changes {
        final List<OptionChanged> seen = new CopyOnWriteArrayList<>();

        void on(@Observes OptionChanged event) {
            seen.add(event);
        }

        // Read through a method: a field of the injected client proxy is not the bean's field.
        List<OptionChanged> seen() {
            return seen;
        }
    }

    @Inject
    SiteScope scope;

    @Inject
    Options options;

    @Inject
    FeatureToggles toggles;

    @Inject
    OptionStore store;

    @Inject
    TestSites sites;

    @Inject
    Changes changes;

    @Inject
    OptionRepository repository;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Test
    void concurrentAddsOfOneNameLetExactlyOneWin() throws Exception {
        SiteId site = sites.create("adds");
        String name = "race-" + UUID.randomUUID();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            int value = i;
            tasks.add(() -> scope.call(site, () -> options.add(name, IntNode.valueOf(value), true)));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(Boolean.class));
        assertThat(results).filteredOn(Boolean.TRUE::equals).hasSize(1);
        int winner = results.indexOf(Boolean.TRUE);
        assertThat(scope.call(site, () -> store.find(site, name))).contains(IntNode.valueOf(winner));
    }

    @Test
    void concurrentSetsOfANewNameAllSucceed() throws Exception {
        SiteId site = sites.create("sets");
        String name = "race-" + UUID.randomUUID();
        changes.seen().clear();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            int value = i;
            tasks.add(() -> scope.call(site, () -> options.set(name, IntNode.valueOf(value))));
        }
        assertThat(Races.run(tasks)).containsOnly(Boolean.TRUE);
        assertThat(changes.seen())
                .filteredOn(event -> event.name().equals(name))
                .hasSize(WRITERS);
        assertThat(changes.seen())
                .filteredOn(event -> event.name().equals(name) && event.before().isMissingNode())
                .hasSize(1);
    }

    @Test
    void concurrentFirstTogglesOfDifferentKeysKeepBoth() throws Exception {
        SiteId site = sites.create("toggles");
        List<Object> results = Races.run(List.of(
                () -> {
                    scope.run(site, () -> toggles.setForSite("alpha", true));
                    return "alpha";
                },
                () -> {
                    scope.run(site, () -> toggles.setForSite("beta", true));
                    return "beta";
                }));
        assertThat(results).containsExactly("alpha", "beta");
        JsonNode stored =
                scope.call(site, () -> store.find(site, FeatureOptions.SITE)).orElseThrow();
        assertThat(FeatureOptions.switches(Optional.of(stored)))
                .containsEntry("alpha", true)
                .containsEntry("beta", true);
    }

    @Test
    void aToggleDoesNotWaitForAnotherTransactionsOptionInsert() throws Exception {
        SiteId site = sites.create("locks");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = pool.submit(() -> scope.run(site, () -> {
                options.add("held-" + UUID.randomUUID(), TextNode.valueOf("x"), true);
                inserted.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> toggle = pool.submit(() -> scope.run(site, () -> toggles.setForSite("alpha", true)));
            try {
                toggle.get(15, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            holder.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(scope.call(site, () -> store.find(site, FeatureOptions.SITE)))
                .hasValueSatisfying(
                        value -> assertThat(value.get("alpha").booleanValue()).isTrue());
    }

    @Test
    void anInsertedValueIsStoredAsTheUnitWritesIt() throws Exception {
        SiteId site = sites.create("text");
        ObjectNode value = JsonNodeFactory.instance.objectNode();
        value.put("b", 1);
        value.putArray("a").add(new BigDecimal("1.50")).add("x");
        String inserted = "ins-" + UUID.randomUUID();
        String updated = "upd-" + UUID.randomUUID();
        scope.run(site, () -> {
            options.add(inserted, value, true);
            options.set(updated, TextNode.valueOf("first"));
            options.set(updated, value);
        });
        List<String> texts = Jdbc.strings(
                dataSource,
                "select value from qp_option where site_id = ? and name in (?, ?) order by name",
                site.value(),
                inserted,
                updated);
        assertThat(texts).hasSize(2);
        assertThat(texts.get(0)).isEqualTo(texts.get(1)).isEqualTo(QuarkPressJsonFormatMapper.write(value));
        assertThat(scope.call(site, () -> store.find(site, inserted))).contains(value);
    }

    @Test
    void aSetAfterAnUnlockedReadSeesTheChangeCommittedInBetween() throws Exception {
        SiteId site = sites.create("stale");
        String name = "stale-" + UUID.randomUUID();
        scope.run(site, () -> options.set(name, IntNode.valueOf(0)));
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch changed = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // The first transaction loads the option without a lock; a second one changes it; then the first writes.
            Future<Boolean> first = pool.submit(() -> scope.call(site, () -> {
                repository.findOption(site, name);
                read.countDown();
                changed.await(30, TimeUnit.SECONDS);
                return options.set(name, IntNode.valueOf(1));
            }));
            assertThat(read.await(30, TimeUnit.SECONDS)).isTrue();
            scope.run(site, () -> options.set(name, IntNode.valueOf(1)));
            changed.countDown();
            // Against the stale copy (0) this would report a change and fire a second event.
            assertThat(first.get(30, TimeUnit.SECONDS)).isFalse();
        } finally {
            pool.shutdownNow();
        }
        assertThat(scope.call(site, () -> store.find(site, name))).contains(IntNode.valueOf(1));
    }
}

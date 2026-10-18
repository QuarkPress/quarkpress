package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteUpdated;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteDomain;
import org.quarkpress.multisite.SiteDomains;
import org.quarkpress.multisite.Sites;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;

class SiteDomainsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(
                    SiteDomainsDbTest.class,
                    Network.class,
                    SwitchableCheck.class,
                    Races.class,
                    Updates.class,
                    TestFeatures.class)
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")));

    @ApplicationScoped
    public static class Updates {
        static final List<SiteUpdated> seen = new CopyOnWriteArrayList<>();

        static final List<SiteUpdated> asynchronous = new CopyOnWriteArrayList<>();

        /** While set, a rename to this name blocks inside its transaction until {@link #release} is counted down. */
        static volatile String holdName;

        static volatile CountDownLatch entered = new CountDownLatch(1);
        static volatile CountDownLatch release = new CountDownLatch(1);

        /** While set, a rename to this name fails. */
        static volatile String failName;

        /** While set, a rename to this name throws an Error. */
        static volatile String errorName;

        void on(@Observes SiteUpdated event) throws InterruptedException {
            seen.add(event);
            if (event.after().name().equals(holdName)) {
                entered.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            if (event.after().name().equals(errorName)) {
                throw new Error("error from the test");
            }
            if (event.after().name().equals(failName)) {
                throw new IllegalStateException("refused by the test");
            }
        }

        void onAsync(@ObservesAsync SiteUpdated event) {
            asynchronous.add(event);
        }

        static void reset() {
            holdName = null;
            failName = null;
            errorName = null;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }

    @Inject
    Sites sites;

    @Inject
    SiteDomains domains;

    @Inject
    SiteScope scope;

    @Inject
    Options options;

    @Inject
    FeatureToggles toggles;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    OptionStore store;

    @BeforeEach
    void reset() {
        SwitchableCheck.DENIED.clear();
        Updates.reset();
    }

    private static String domain(String label) {
        return Network.unique(label) + ".example";
    }

    private SiteInfo site(String label) {
        return sites.create(NewSite.of(domain(label), "/", label));
    }

    @Test
    void anAliasIsNormalizedAndListed() {
        SiteInfo site = site("owner");
        String alias = domain("alias");
        SiteDomain added = domains.add(site.id(), " " + alias.toUpperCase(Locale.ROOT) + " ", true);
        assertThat(added.domain()).isEqualTo(alias);
        assertThat(added.site()).isEqualTo(site.id());
        assertThat(added.redirectToPrimary()).isTrue();
        assertThat(Requests.call(() -> domains.find(alias.toUpperCase(Locale.ROOT))))
                .contains(added);
        assertThat(Requests.call(() -> domains.forSite(site.id()))).containsExactly(added);
    }

    @Test
    void anAliasCannotBeASitesDomainOrTakenTwice() {
        SiteInfo site = site("first");
        SiteInfo other = site("second");
        assertThatThrownBy(() -> domains.add(site.id(), other.domain(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain " + other.domain() + " is the address of a site");
        String alias = domain("twice");
        domains.add(site.id(), alias, false);
        assertThatThrownBy(() -> domains.add(other.id(), alias, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain " + alias + " is already an alias of site "
                        + site.id().value());
        assertThatThrownBy(() -> domains.add(org.quarkpress.core.SiteId.of(Long.MAX_VALUE), domain("x"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("site " + Long.MAX_VALUE + " does not exist");
    }

    @Test
    void aSiteCannotTakeAnAliasDomain() {
        SiteInfo site = site("aliased");
        String alias = domain("taken");
        domains.add(site.id(), alias, false);
        assertThatThrownBy(() -> sites.create(NewSite.of(alias, "/", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain " + alias + " is an alias of site "
                        + site.id().value());
    }

    @Test
    void anAliasCannotTakeTheWwwFormOfASiteDomain() {
        SiteInfo owner = site("www-owner");
        SiteInfo other = site("www-other");
        assertThatThrownBy(() -> domains.add(owner.id(), "www." + other.domain(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain www." + other.domain() + " is the address of a site");
        assertThatThrownBy(() -> domains.add(owner.id(), "www.localhost", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain www.localhost is the address of a site");
        String bare = domain("bare");
        sites.create(NewSite.of("www." + bare, "/", "www site"));
        assertThatThrownBy(() -> domains.add(owner.id(), bare, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain " + bare + " is the address of a site");
    }

    @Test
    void aSiteCannotTakeTheWwwFormOfAnAlias() {
        SiteInfo owner = site("alias-owner");
        String alias = domain("plain-alias");
        domains.add(owner.id(), alias, false);
        assertThatThrownBy(() -> sites.create(NewSite.of("www." + alias, "/", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain " + alias + " is an alias of site "
                        + owner.id().value());
        String bare = domain("www-alias");
        domains.add(owner.id(), "www." + bare, false);
        assertThatThrownBy(() -> sites.create(NewSite.of(bare, "/", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the domain www." + bare + " is an alias of site "
                        + owner.id().value());
    }

    @Test
    void removingAnAlias() {
        SiteInfo site = site("remove");
        String alias = domain("gone");
        domains.add(site.id(), alias, true);
        assertThat(domains.remove(alias)).isTrue();
        assertThat(domains.remove(alias)).isFalse();
        assertThat(Requests.call(() -> domains.find(alias))).isEmpty();
    }

    @Test
    void aliasesGoWithTheirSite() {
        SiteInfo site = site("cascade");
        domains.add(site.id(), domain("with"), false);
        sites.delete(site.id());
        assertThat(Requests.call(() -> domains.forSite(site.id()))).isEmpty();
    }

    @Test
    void anAliasAndASiteCannotTakeOneDomainConcurrently() throws Exception {
        SiteInfo owner = site("race-owner");
        String contested = domain("contested");
        List<Callable<Object>> tasks = List.of(
                () -> domains.add(owner.id(), contested, false), () -> sites.create(NewSite.of(contested, "/", "x")));
        List<Object> results = Races.run(tasks);
        assertThat(results)
                .filteredOn(IllegalArgumentException.class::isInstance)
                .singleElement()
                .satisfies(failure -> assertThat(((Throwable) failure).getMessage())
                        .isIn(
                                "the domain " + contested + " is the address of a site",
                                "the domain " + contested + " is an alias of site "
                                        + owner.id().value()));
        assertThat(results)
                .filteredOn(r -> r instanceof SiteDomain || r instanceof SiteInfo)
                .hasSize(1);
    }

    /** The name once it equals {@code expected}, polled for up to 10 seconds; the last name read otherwise. */
    private String nameOf(SiteInfo site, String expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String name = sites.find(site.id()).orElseThrow().name();
        while (!name.equals(expected) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            name = sites.find(site.id()).orElseThrow().name();
        }
        return name;
    }

    @Test
    void writingTheBlognameRenamesTheSiteAfterCommit() throws Exception {
        SiteInfo site = site("named");
        Updates.seen.clear();
        scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Renamed")));
        assertThat(nameOf(site, "Renamed")).isEqualTo("Renamed");
        assertThat(Updates.seen)
                .filteredOn(event -> event.site().equals(site.id()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.before().name()).isEqualTo("named");
                    assertThat(event.after().name()).isEqualTo("Renamed");
                });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Updates.asynchronous.stream().noneMatch(e -> e.site().equals(site.id()))
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(Updates.asynchronous)
                .filteredOn(event -> event.site().equals(site.id()))
                .singleElement()
                .satisfies(event -> assertThat(event.after().name()).isEqualTo("Renamed"));
        scope.run(site.id(), () -> options.set("blogname", IntNode.valueOf(5)));
        assertThat(sites.find(site.id()).orElseThrow().name()).isEqualTo("Renamed");
        String clef = new String(Character.toChars(0x1D11E));
        String longName = clef.repeat(300);
        scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf(longName)));
        String stored = nameOf(site, clef.repeat(255));
        assertThat(stored.codePointCount(0, stored.length())).isEqualTo(255);
        assertThat(stored).isEqualTo(clef.repeat(255));
    }

    @Test
    void aDeniedCapabilityWritesNothing() {
        SiteInfo site = site("denied");
        String alias = domain("denied-alias");
        SwitchableCheck.DENIED.add(NetworkCapabilities.MANAGE_SITES);
        assertThatThrownBy(() -> domains.add(site.id(), alias, false))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability manage_sites");
        assertThat(Requests.call(() -> domains.find(alias))).isEmpty();
    }

    @Test
    void anAliasCannotTakeTheMainSitesDomainOrItsWwwForm() {
        SiteInfo owner = site("main-owner");
        String main = sites.find(org.quarkpress.core.SiteId.of(1)).orElseThrow().domain();
        for (String form : List.of(main, "www." + main)) {
            assertThatThrownBy(() -> domains.add(owner.id(), form, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the domain " + form + " is the address of a site");
        }
    }

    @Test
    void anAliasAddFromAnNetworkLockHolderIsRefusedQuickly() {
        SiteInfo owner = site("lock-owner");
        String alias = domain("lock-alias");
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> assertThatThrownBy(() -> QuarkusTransaction.requiringNew()
                                .run(() -> {
                                    toggles.setNetworkDefault("demo", false);
                                    domains.add(owner.id(), alias, false);
                                }))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("a network write cannot start inside a transaction that already holds the network"
                                + " lock; call it outside that transaction")));
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> assertThatThrownBy(
                                () -> QuarkusTransaction.requiringNew().run(() -> {
                                    toggles.setNetworkDefault("demo", false);
                                    domains.remove(alias);
                                }))
                        .isInstanceOf(IllegalStateException.class)));
        assertThat(Requests.call(() -> domains.find(alias))).isEmpty();
    }

    @Test
    void aNonTextBlognameChangesNothing() throws Exception {
        SiteInfo site = site("non-text");
        Updates.seen.clear();
        scope.run(site.id(), () -> options.set("blogname", IntNode.valueOf(5)));
        // a later text value is renamed after the non-text one was handled (renames of one site run in order)
        scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("After")));
        assertThat(nameOf(site, "After")).isEqualTo("After");
        assertThat(Updates.seen)
                .filteredOn(event -> event.site().equals(site.id()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.before().name()).isEqualTo("non-text");
                    assertThat(event.after().name()).isEqualTo("After");
                });
    }

    @Test
    void anAliasThatNormalizesToNothingOrZeroIsRefusedAndFindOfSuchTextIsEmpty() {
        SiteInfo site = site("empty-alias");
        for (String bad : List.of("0", "!!", "")) {
            assertThatThrownBy(() -> domains.add(site.id(), bad, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a site domain must not be empty");
            assertThat(Requests.call(() -> domains.find(bad))).isEmpty();
        }
    }

    @Test
    void removeNeedsManageSites() {
        SiteInfo site = site("remove-denied");
        String alias = domain("remove-denied-alias");
        domains.add(site.id(), alias, false);
        SwitchableCheck.DENIED.add(NetworkCapabilities.MANAGE_SITES);
        assertThatThrownBy(() -> domains.remove(alias))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability manage_sites");
        assertThat(Requests.call(() -> domains.find(alias))).isPresent();
    }

    @Test
    void syncsThatCouldRunOutOfOrderEndWithTheLastCommittedBlogname() throws Exception {
        SiteInfo site = site("order");
        Updates.holdName = "First";
        try {
            scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("First")));
            // the first rename now sits inside its transaction, holding the network lock
            assertThat(Updates.entered.await(10, TimeUnit.SECONDS)).isTrue();
            scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Second")));
            scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Last")));
        } finally {
            Updates.release.countDown();
        }
        assertThat(nameOf(site, "Last")).isEqualTo("Last");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select name from qp_site where id = ?",
                        site.id().value()))
                .containsExactly("Last");
    }

    @Test
    void aRenameWhileTheWritersThreadHasASuspendedTransactionInsertingRowsOfTheSiteDoesNotWait() throws Exception {
        SiteInfo site = site("suspended");
        long started = System.nanoTime();
        scope.run(site.id(), () -> {
            options.add("suspended-" + Network.unique("o"), TextNode.valueOf("x"), true);
            QuarkusTransaction.requiringNew().run(() -> options.set("blogname", TextNode.valueOf("Inner")));
        });
        // the writer was not held up by the rename (on MySQL it would have waited 50 s for the outer insert's lock)
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(10);
        assertThat(nameOf(site, "Inner")).isEqualTo("Inner");
    }

    @Test
    void aWriterInANestedScopeOfANetworkLockHolderIsRenamedEventually() throws Exception {
        SiteInfo site = site("nested");
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
            toggles.setNetworkDefault("demo", false);
            scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Nested")));
        }));
        assertThat(nameOf(site, "Nested")).isEqualTo("Nested");
    }

    @Test
    void aFailedRenameKeepsTheNameAndTheCommittedBlognameAndLogsAWarning() throws Exception {
        SiteInfo site = site("failing");
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        java.util.logging.Logger log =
                java.util.logging.Logger.getLogger("org.quarkpress.multisite.runtime.BlognameSync");
        log.addHandler(handler);
        try {
            Updates.failName = "Doomed";
            scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Doomed")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (records.stream().noneMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                    && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
        } finally {
            log.removeHandler(handler);
            Updates.failName = null;
        }
        assertThat(records)
                .anySatisfy(r -> assertThat(r.getMessage()).contains("renaming it after its blogname option changed"));
        assertThat(sites.find(site.id()).orElseThrow().name()).isEqualTo("failing");
        assertThat(scope.call(site.id(), () -> store.find(site.id(), "blogname")))
                .contains(TextNode.valueOf("Doomed"));
    }

    @Test
    void anErrorInARenameDoesNotStopLaterRenamesOfTheSite() throws Exception {
        SiteInfo site = site("error");
        Updates.errorName = "Boom";
        scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Boom")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Updates.seen.stream()
                        .noneMatch(e ->
                                e.site().equals(site.id()) && e.after().name().equals("Boom"))
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Updates.errorName = null;
        assertThat(sites.find(site.id()).orElseThrow().name()).isEqualTo("error");
        scope.run(site.id(), () -> options.set("blogname", TextNode.valueOf("Fine")));
        assertThat(nameOf(site, "Fine")).isEqualTo("Fine");
    }
}

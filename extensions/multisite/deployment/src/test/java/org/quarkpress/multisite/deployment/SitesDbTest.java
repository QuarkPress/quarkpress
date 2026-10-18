package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.CapabilityDeniedException;
import org.quarkpress.core.NetworkCapabilities;
import org.quarkpress.core.OptionChanged;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteCreated;
import org.quarkpress.core.SiteDeleted;
import org.quarkpress.core.SiteFeature;
import org.quarkpress.core.SiteFlagsChanged;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteStatus;
import org.quarkpress.core.SiteUpdated;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.FeatureResolver;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteChanges;
import org.quarkpress.multisite.SiteFlag;
import org.quarkpress.multisite.Sites;
import org.quarkpress.multisite.runtime.NetworkCalls;
import org.quarkpress.multisite.runtime.SiteAddresses;
import org.quarkpress.multisite.runtime.ThreadPresetSite;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.persistence.runtime.SiteDefaults;

class SitesDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(
                    SitesDbTest.class,
                    Network.class,
                    Log.class,
                    ThreadPresetSite.class,
                    NetworkCalls.class,
                    SwitchableCheck.class,
                    TestFeatures.class,
                    Races.class)
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", false, "Demo")));

    static final SiteId MAIN = SiteId.of(1);
    static final String RESERVED =
            "page, comments, blog, files, feed, wp-admin, wp-content, wp-includes, wp-json," + " embed";

    record View(boolean enabled, long visibleElsewhere, boolean inTransaction) {}

    @ApplicationScoped
    public static class Log {
        static final List<Object> events = new CopyOnWriteArrayList<>();
        static final List<SiteId> gated = new CopyOnWriteArrayList<>();
        static final BlockingQueue<SiteCreated> async = new LinkedBlockingQueue<>();
        static final List<SiteId> deletedGated = new CopyOnWriteArrayList<>();
        static final Map<SiteId, View> views = new ConcurrentHashMap<>();
        static final Map<SiteId, Boolean> flagReads = new ConcurrentHashMap<>();
        static volatile boolean readFeaturesOnFlags;
        static volatile String failOnTitle = "";

        @Inject
        FeatureResolver features;

        @Inject
        @DataSource("quarkpress")
        AgroalDataSource dataSource;

        /** Counts the site's row from a connection of its own, which sees only committed rows. */
        long otherConnectionCount(SiteId site) {
            try {
                return CompletableFuture.supplyAsync(
                                () -> Jdbc.count(dataSource, "select count(*) from qp_site where id = ?", site.value()))
                        .get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        void created(@Observes SiteCreated event) {
            events.add(event);
            if (event.created().name().equals(failOnTitle)) {
                throw new IllegalStateException("observer refused " + failOnTitle);
            }
        }

        void updated(@Observes SiteUpdated event) {
            events.add(event);
        }

        void flags(@Observes SiteFlagsChanged event) {
            events.add(event);
            if (readFeaturesOnFlags) {
                flagReads.put(event.site(), features.forSite(event.site()).isEnabled("demo"));
            }
        }

        void deleted(@Observes SiteDeleted event) {
            events.add(event);
        }

        void option(@Observes OptionChanged event) {
            events.add(event);
        }

        @SiteFeature("demo")
        void gated(@Observes SiteCreated event) {
            gated.add(event.site());
            boolean enabled = features.forSite(event.site()).isEnabled("demo");
            long elsewhere = otherConnectionCount(event.site());
            views.put(event.site(), new View(enabled, elsewhere, QuarkusTransaction.isActive()));
        }

        @SiteFeature("demo")
        void goneGated(@Observes SiteDeleted event) {
            deletedGated.add(event.site());
        }

        void later(@ObservesAsync SiteCreated event) {
            async.add(event);
        }
    }

    @Inject
    Sites sites;

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    SiteScope scope;

    @Inject
    OptionStore store;

    @Inject
    Options options;

    @Inject
    FeatureToggles toggles;

    @Inject
    FeatureResolver features;

    @Inject
    SiteContext context;

    @Inject
    Network network;

    @Inject
    NetworkCalls networkCalls;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @BeforeEach
    void reset() {
        Log.events.clear();
        Log.gated.clear();
        Log.deletedGated.clear();
        Log.views.clear();
        Log.flagReads.clear();
        Log.readFeaturesOnFlags = false;
        Log.failOnTitle = "";
        SwitchableCheck.DENIED.clear();
    }

    private static String domain(String label) {
        return Network.unique(label) + ".example";
    }

    private java.util.Optional<SiteInfo> at(String domain, String path) {
        return Requests.call(() -> sites.findByAddress(domain, path));
    }

    private <T> List<T> events(Class<T> type) {
        return Log.events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Test
    void createsASiteWithWordPressDefaults() throws Exception {
        String domain = domain("new");
        SiteInfo site = sites.create(NewSite.of(domain, "/", "Blog"));
        assertThat(site.domain()).isEqualTo(domain);
        assertThat(site.path()).isEqualTo("/");
        assertThat(site.name()).isEqualTo("Blog");
        assertThat(site.publicFlag()).isEqualTo(1);
        assertThat(site.status()).isEqualTo(SiteStatus.ACTIVE);
        Map<String, JsonNode> stored = scope.call(site.id(), () -> store.autoloaded(site.id()));
        SiteDefaults.forNewSite("Blog")
                .forEach((name, value) -> assertThat(JsonValues.sameValue(stored.get(name), value))
                        .as("option %s is %s", name, stored.get(name))
                        .isTrue());
        assertThat(events(SiteCreated.class)).extracting(SiteCreated::site).containsExactly(site.id());
        assertThat(events(SiteFlagsChanged.class)).isEmpty();
        assertThat(events(OptionChanged.class)).isEmpty();
    }

    @Test
    void aSiteCreatedWithANonDefaultPublicValueFiresFlagsChangedFirst() {
        SiteInfo site = sites.create(NewSite.of(domain("hidden"), "/", "Hidden").withPublic(0));
        assertThat(site.publicFlag()).isZero();
        List<Object> mine = Log.events.stream()
                .filter(event ->
                        (event instanceof SiteFlagsChanged flags && flags.site().equals(site.id()))
                                || (event instanceof SiteCreated creation
                                        && creation.site().equals(site.id())))
                .toList();
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0)).isInstanceOfSatisfying(SiteFlagsChanged.class, flags -> {
            assertThat(flags.before().publicFlag()).isEqualTo(1);
            assertThat(flags.after().publicFlag()).isZero();
        });
        assertThat(mine.get(1)).isInstanceOf(SiteCreated.class);
    }

    @Test
    void normalizesTheAddressLikeWordPress() {
        String label = Network.unique("Norm");
        SiteInfo site = sites.create(NewSite.of(" " + label + ".Example.COM ", "News", "N"));
        assertThat(site.domain()).isEqualTo(label.toLowerCase(java.util.Locale.ROOT) + ".example.com");
        assertThat(site.path()).isEqualTo("/news/");
        assertThat(at(" " + label + ".EXAMPLE.com", "/news")).contains(site);
    }

    @Test
    void refusesTakenAndReservedAddresses() {
        String domain = domain("taken");
        sites.create(NewSite.of(domain, "/", "First"));
        assertThatThrownBy(() -> sites.create(NewSite.of(domain, "/", "Second")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site already exists at " + domain + "/");
        assertThatThrownBy(() -> sites.create(NewSite.of("localhost", "/wp-admin/", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the first path segment 'wp-admin' is reserved for WordPress on subdirectory sites of"
                        + " the network; reserved: " + RESERVED);
        assertThatThrownBy(() -> sites.create(NewSite.of("localhost", "/Feed/", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("the first path segment 'feed' is reserved");
        assertThat(sites.create(NewSite.of(domain("other"), "/blog/", "Elsewhere"))
                        .path())
                .isEqualTo("/blog/");
        assertThatThrownBy(() -> sites.create(NewSite.of(domain + ":8080", "/", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site domain has no port: " + domain + ":8080");
        assertThatThrownBy(() -> sites.create(NewSite.of(domain("long"), "/", "x".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site name has at most 255 characters");
    }

    @Test
    void anEmptyTitleBecomesSiteNAndABlankOneIsKept() throws Exception {
        SiteInfo empty = sites.create(NewSite.of(domain("empty"), "/", ""));
        assertThat(empty.name()).isEqualTo("Site " + empty.id().value());
        assertThat(scope.call(empty.id(), () -> store.find(empty.id(), "blogname")))
                .contains(TextNode.valueOf("Site " + empty.id().value()));
        SiteInfo blank = sites.create(NewSite.of(domain("blank"), "/", " "));
        assertThat(blank.name()).isEqualTo(" ");
    }

    @Test
    void featuresChosenAtCreationWinOverNetworkDefaults() throws Exception {
        Requests.run(() -> toggles.setNetworkDefault("demo", true));
        try {
            SiteInfo inherits = sites.create(NewSite.of(domain("inherit"), "/", "Inherits"));
            SiteInfo opts =
                    sites.create(NewSite.of(domain("opt-out"), "/", "Opts out").withFeatures(Map.of("demo", false)));
            assertThat(Requests.call(() -> features.forSite(inherits.id()).isEnabled("demo")))
                    .isTrue();
            assertThat(Requests.call(() -> features.forSite(opts.id()).isEnabled("demo")))
                    .isFalse();
        } finally {
            Requests.run(() -> toggles.clearNetworkDefault("demo"));
        }
    }

    @Test
    void unknownFeatureKeysCreateNothing() {
        String domain = domain("unknown");
        assertThatThrownBy(() -> sites.create(NewSite.of(domain, "/", "x").withFeatures(Map.of("nope", true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unknown QuarkPress feature 'nope'");
        assertThat(at(domain, "/")).isEmpty();
    }

    @Test
    void aGatedObserverFollowsTheNewSitesFeatures() {
        SiteInfo on = sites.create(NewSite.of(domain("on"), "/", "On").withFeatures(Map.of("demo", true)));
        SiteInfo off = sites.create(NewSite.of(domain("off"), "/", "Off").withFeatures(Map.of("demo", false)));
        assertThat(Log.gated).contains(on.id()).doesNotContain(off.id());
    }

    @Test
    void asyncObserversHearTheCreationAfterCommit() throws Exception {
        SiteInfo site = sites.create(NewSite.of(domain("async"), "/", "Async"));
        SiteCreated heard = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (heard == null && System.nanoTime() < deadline) {
            SiteCreated next = Log.async.poll(100, TimeUnit.MILLISECONDS);
            if (next != null && next.site().equals(site.id())) {
                heard = next;
            }
        }
        assertThat(heard).isNotNull();
    }

    @Test
    void renamingSyncsTheBlogname() throws Exception {
        SiteInfo site = sites.create(NewSite.of(domain("rename"), "/", "Old"));
        SiteInfo renamed = sites.update(site.id(), SiteChanges.none().withName("New"));
        assertThat(renamed.name()).isEqualTo("New");
        assertThat(scope.call(site.id(), () -> store.find(site.id(), "blogname")))
                .contains(TextNode.valueOf("New"));
        assertThat(events(OptionChanged.class)).singleElement().satisfies(event -> {
            assertThat(event.name()).isEqualTo("blogname");
            assertThat(event.before()).isEqualTo(TextNode.valueOf("Old"));
        });
        assertThat(events(SiteUpdated.class)).singleElement().satisfies(event -> {
            assertThat(event.before().name()).isEqualTo("Old");
            assertThat(event.after().name()).isEqualTo("New");
        });
    }

    @Test
    void movingASiteChecksTheNewAddress() {
        SiteInfo site = sites.create(NewSite.of(domain("move"), "/", "Move"));
        String target = domain("target");
        SiteInfo moved =
                sites.update(site.id(), SiteChanges.none().withDomain(target).withPath("Shop"));
        assertThat(moved.domain()).isEqualTo(target);
        assertThat(moved.path()).isEqualTo("/shop/");
        SiteInfo blocker = sites.create(NewSite.of(domain("blocker"), "/", "Blocker"));
        assertThatThrownBy(() -> sites.update(
                        blocker.id(), SiteChanges.none().withDomain(target).withPath("/shop/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site already exists at " + target + "/shop/");
        assertThat(sites.update(site.id(), SiteChanges.none())).isEqualTo(moved);
    }

    @Test
    void theMainSiteMayMoveButKeepsItsFlagsAndExistence() {
        String moved = domain("main");
        try {
            assertThat(sites.update(MAIN, SiteChanges.none().withDomain(moved)).domain())
                    .isEqualTo(moved);
        } finally {
            sites.update(MAIN, SiteChanges.none().withDomain("localhost").withPath("/"));
        }
        assertThatThrownBy(() -> sites.setFlag(MAIN, SiteFlag.SPAM, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the main site's flags cannot change");
        assertThatThrownBy(() -> sites.delete(MAIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the main site cannot be deleted");
    }

    @Test
    void flagsFireUpdatedThenFlagsChanged() {
        SiteInfo site = sites.create(NewSite.of(domain("flags"), "/", "Flags"));
        Log.events.clear();
        SiteInfo archived = sites.setFlag(site.id(), SiteFlag.ARCHIVED, true);
        assertThat(archived.status()).isEqualTo(SiteStatus.ARCHIVED);
        assertThat(Log.events).hasSize(2);
        assertThat(Log.events.get(0)).isInstanceOf(SiteUpdated.class);
        assertThat(Log.events.get(1)).isInstanceOf(SiteFlagsChanged.class);
        Log.events.clear();
        assertThat(sites.setFlag(site.id(), SiteFlag.ARCHIVED, true)).isEqualTo(archived);
        assertThat(Log.events).isEmpty();
        SiteInfo hidden = sites.setPublic(site.id(), 0);
        assertThat(hidden.publicFlag()).isZero();
        assertThat(events(SiteFlagsChanged.class)).hasSize(1);
        assertThatThrownBy(() -> sites.setPublic(site.id(), 40_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("public must be between -32768 and 32767: 40000");
    }

    @Test
    void deletingRemovesTheSiteAndItsRows() {
        SiteInfo site = sites.create(NewSite.of(domain("delete"), "/", "Delete"));
        scope.run(site.id(), () -> options.set("extra", TextNode.valueOf("x")));
        network.alias(site.id(), domain("delete-alias"), false);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_site_domain where site_id = ?",
                        site.id().value()))
                .isOne();
        sites.delete(site.id());
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_site_domain where site_id = ?",
                        site.id().value()))
                .isZero();
        assertThat(Requests.call(() -> sites.find(site.id()))).isEmpty();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_option where site_id = ?",
                        site.id().value()))
                .isZero();
        assertThat(events(SiteDeleted.class)).extracting(SiteDeleted::deleted).containsExactly(site);
        assertThatThrownBy(() -> sites.delete(site.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("site " + site.id().value() + " does not exist");
    }

    @Test
    void anObserverFailureRollsTheCreationBack() {
        String domain = domain("fail");
        Log.failOnTitle = "fail-" + domain;
        assertThatThrownBy(() -> sites.create(NewSite.of(domain, "/", "fail-" + domain)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("observer refused fail-" + domain);
        assertThat(at(domain, "/")).isEmpty();
    }

    @Test
    void aDeniedCapabilityWritesNothing() {
        String domain = domain("denied");
        SwitchableCheck.DENIED.add(NetworkCapabilities.CREATE_SITES);
        assertThatThrownBy(() -> sites.create(NewSite.of(domain, "/", "x")))
                .isInstanceOf(CapabilityDeniedException.class)
                .hasMessage("the caller lacks the network capability create_sites");
        assertThat(at(domain, "/")).isEmpty();
        SwitchableCheck.DENIED.clear();
        SiteInfo site = sites.create(NewSite.of(domain, "/", "x"));
        SwitchableCheck.DENIED.add(NetworkCapabilities.MANAGE_SITES);
        SwitchableCheck.DENIED.add(NetworkCapabilities.DELETE_SITES);
        assertThatThrownBy(() -> sites.setFlag(site.id(), SiteFlag.SPAM, true))
                .isInstanceOf(CapabilityDeniedException.class);
        assertThatThrownBy(() -> sites.update(site.id(), SiteChanges.none().withName("y")))
                .isInstanceOf(CapabilityDeniedException.class);
        assertThatThrownBy(() -> sites.setPublic(site.id(), 0)).isInstanceOf(CapabilityDeniedException.class);
        assertThatThrownBy(() -> sites.delete(site.id())).isInstanceOf(CapabilityDeniedException.class);
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
    }

    @Test
    void aCreationAfterTheCallerWroteMainSiteRowsDoesNotWait() throws Exception {
        String domain = domain("after-main");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<SiteInfo> creation = pool.submit(() -> scope.call(MAIN, () -> {
                options.add("held-" + UUID.randomUUID(), TextNode.valueOf("x"), true);
                return sites.create(NewSite.of(domain, "/", "After main"));
            }));
            assertThat(creation.get(15, TimeUnit.SECONDS).domain()).isEqualTo(domain);
        } finally {
            pool.shutdownNow();
        }
        assertThat(at(domain, "/")).isPresent();
    }

    @Test
    void changingTheBoundSiteFromItsTransactionIsRefused() throws Exception {
        SiteInfo site = sites.create(NewSite.of(domain("bound-change"), "/", "Bound"));
        String message = "change site " + site.id().value()
                + " outside a transaction bound to it: the caller's rows of that site lock its record";
        scope.run(site.id(), () -> {
            assertThatThrownBy(() -> sites.update(site.id(), SiteChanges.none().withName("x")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(message);
            assertThatThrownBy(() -> sites.setFlag(site.id(), SiteFlag.SPAM, true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(message);
            assertThatThrownBy(() -> sites.setPublic(site.id(), 0))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(message);
            assertThatThrownBy(() -> sites.delete(site.id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(message);
        });
        assertThat(sites.find(site.id())).contains(site);
    }

    @Test
    void aCreationFromABoundRequestCommitsOnItsOwn() {
        String domain = domain("bound");
        assertThatThrownBy(() -> scope.run(MAIN, () -> {
                    sites.create(NewSite.of(domain, "/", "Bound"));
                    throw new IllegalStateException("caller rolls back");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("caller rolls back");
        assertThat(at(domain, "/")).isPresent();
    }

    @Test
    void concurrentCreatesOfOneAddressLetOneWin() throws Exception {
        String domain = domain("race");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String title = "Race " + i;
            tasks.add(() -> sites.create(NewSite.of(domain, "/", title)));
        }
        List<Object> results = Races.run(tasks);
        assertThat(results).filteredOn(SiteInfo.class::isInstance).hasSize(1);
        assertThat(results)
                .filteredOn(IllegalArgumentException.class::isInstance)
                .hasSize(3)
                .allSatisfy(failure ->
                        assertThat((Throwable) failure).hasMessage("a site already exists at " + domain + "/"));
    }

    @Test
    void listingPagesThroughSitesById() {
        SiteInfo first = sites.create(NewSite.of(domain("list-a"), "/", "A"));
        SiteInfo second = sites.create(NewSite.of(domain("list-b"), "/", "B"));
        List<SiteInfo> all = Requests.call(() -> sites.list(0, 1000));
        assertThat(all).contains(first, second);
        assertThat(all.indexOf(first)).isLessThan(all.indexOf(second));
        assertThat(Requests.call(() -> sites.list(0, 1))).hasSize(1);
        assertThatThrownBy(() -> sites.list(-1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("offset must be at least 0 and limit between 1 and 1000");
        assertThatThrownBy(() -> sites.list(0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("offset must be at least 0 and limit between 1 and 1000");
    }

    @Test
    void anOffsetWhoseLastIndexWouldOverflowIsRefused() {
        assertThatThrownBy(() -> Requests.call(() -> sites.list(Integer.MAX_VALUE, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("offset must be at most 2147483638 for limit 10");
        // the largest offset whose last index is still an int lists nothing, as there are not that many sites
        assertThat(Requests.call(() -> sites.list(Integer.MAX_VALUE - 9, 10))).isEmpty();
    }

    private static final String UNREACHABLE = "the site path %s cannot be reached by requests";
    private static final String NON_APPLICATION =
            "the site path %s lies under the non-application root, where requests" + " are not resolved to sites";

    @Test
    void featuresTheRequestReadBeforeADeletionDoNotOutliveIt() {
        Requests.run(() -> toggles.setNetworkDefault("demo", true));
        try {
            SiteInfo site =
                    sites.create(NewSite.of(domain("cached"), "/", "Cached").withFeatures(Map.of("demo", false)));
            Requests.run(() -> {
                assertThat(features.forSite(site.id()).isEnabled("demo")).isFalse();
                sites.delete(site.id());
                // the site's own switch is gone with its options: the cached "off" must not survive
                assertThat(features.forSite(site.id()).isEnabled("demo")).isTrue();
            });
        } finally {
            Requests.run(() -> toggles.clearNetworkDefault("demo"));
        }
    }

    @Test
    void aRequestBoundToASiteSeesTheNewNameAfterRenamingIt() {
        SiteInfo site = sites.create(NewSite.of(domain("renamed-bound"), "/", "Old"));
        ThreadPresetSite.PRESET.set(Binding.site(site.id()));
        try {
            Requests.run(() -> {
                assertThat(context.options().get("blogname")).contains(TextNode.valueOf("Old"));
                sites.update(site.id(), SiteChanges.none().withName("New"));
                assertThat(context.options().get("blogname")).contains(TextNode.valueOf("New"));
            });
        } finally {
            ThreadPresetSite.PRESET.remove();
        }
    }

    @Test
    void aNetworkWriteTwoScopesDeepFromALockHolderIsRefusedQuickly() {
        SiteInfo a = sites.create(NewSite.of(domain("deep-a"), "/", "A"));
        SiteInfo b = sites.create(NewSite.of(domain("deep-b"), "/", "B"));
        // a site outside the spawn chain: a change of a or b from there is refused for that reason first
        SiteInfo c = sites.create(NewSite.of(domain("deep-c"), "/", "C"));
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> {
                    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                                toggles.setNetworkDefault("demo", false);
                                try {
                                    scope.call(
                                            a.id(),
                                            () -> scope.call(
                                                    b.id(),
                                                    () -> sites.update(
                                                            c.id(),
                                                            SiteChanges.none().withName("deep"))));
                                } catch (RuntimeException e) {
                                    throw e;
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            }))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "a network write cannot start inside a transaction that already holds the network lock;"
                                            + " call it outside that transaction");
                }));
        assertThat(Requests.call(() -> sites.find(c.id()))).contains(c);
    }

    private static String boundChange(SiteInfo site) {
        return "change site " + site.id().value()
                + " outside a transaction bound to it: the caller's rows of that site lock its record";
    }

    @Test
    void aScopeOpenedFromATransactionBoundToASiteCannotChangeThatSite() {
        SiteInfo a = sites.create(NewSite.of(domain("spawner-a"), "/", "A"));
        SiteInfo b = sites.create(NewSite.of(domain("spawner-b"), "/", "B"));
        SiteInfo c = sites.create(NewSite.of(domain("spawner-c"), "/", "C"));
        SiteInfo other = sites.create(NewSite.of(domain("spawner-other"), "/", "Other"));
        List<Runnable> changes = List.of(
                () -> sites.delete(a.id()),
                () -> sites.update(a.id(), SiteChanges.none().withDomain(domain("spawner-moved"))),
                () -> sites.setFlag(a.id(), SiteFlag.SPAM, true),
                () -> sites.setPublic(a.id(), 0));
        // without the refusal the first change waits for the spawner's locks (on PostgreSQL until the lock timeout)
        assertTimeoutPreemptively(
                Duration.ofSeconds(8),
                () -> scope.run(a.id(), () -> {
                    // the insert's foreign key check locks a's record until this transaction ends
                    options.add("held-" + UUID.randomUUID(), TextNode.valueOf("x"), true);
                    for (Runnable change : changes) {
                        assertThatThrownBy(() -> scope.run(b.id(), change))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage(boundChange(a));
                        assertThatThrownBy(() -> scope.run(b.id(), () -> scope.run(c.id(), change)))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage(boundChange(a));
                        assertThatThrownBy(() -> networkCalls.call(() -> {
                                    change.run();
                                    return null;
                                }))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage(boundChange(a));
                    }
                    // the scope's own transaction binds b, too
                    assertThatThrownBy(() -> scope.run(b.id(), () -> scope.run(c.id(), () -> sites.delete(b.id()))))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(boundChange(b));
                    // a site no thread of the chain is bound to can still change
                    scope.run(
                            b.id(),
                            () -> sites.update(other.id(), SiteChanges.none().withName("Changed")));
                }));
        assertThat(Requests.call(() -> sites.find(a.id()))).contains(a);
        assertThat(Requests.call(() -> sites.find(b.id()))).contains(b);
        assertThat(Requests.call(() -> sites.find(other.id())))
                .map(SiteInfo::name)
                .contains("Changed");
    }

    @Test
    void aScopeOpenedOutsideATransactionMayChangeTheCallersSite() {
        SiteInfo a = sites.create(NewSite.of(domain("no-tx-a"), "/", "A"));
        SiteInfo b = sites.create(NewSite.of(domain("no-tx-b"), "/", "B"));
        ThreadPresetSite.PRESET.set(Binding.site(a.id()));
        try {
            // the request is bound to a but has no transaction, so it holds no lock on a's record
            Requests.run(() -> scope.run(
                    b.id(), () -> sites.update(a.id(), SiteChanges.none().withName("Free"))));
        } finally {
            ThreadPresetSite.PRESET.remove();
        }
        assertThat(Requests.call(() -> sites.find(a.id()))).map(SiteInfo::name).contains("Free");
    }

    @Test
    void aNetworkBoundCallerThatReadASiteBeforeAnotherWriteChangedItIsToldToRetry() throws Exception {
        SiteInfo site = sites.create(NewSite.of(domain("stale"), "/", "Before"));
        assertThatThrownBy(() -> networkCalls.call(() -> {
                    // loads the record into the network transaction's persistence context
                    sites.find(site.id());
                    Requests.outside(
                            () -> sites.update(site.id(), SiteChanges.none().withName("Elsewhere")));
                    return sites.update(site.id(), SiteChanges.none().withName("Here"));
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("site " + site.id().value() + " changed concurrently; try again");
        assertThat(Requests.call(() -> sites.find(site.id())))
                .map(SiteInfo::name)
                .contains("Elsewhere");
    }

    /** Whether the failure is a database lock timeout: PostgreSQL's SQLSTATE 55P03 or MySQL's error 1205. */
    static boolean lockTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && ("55P03".equals(sql.getSQLState()) || sql.getErrorCode() == 1205)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aNetworkWriteWaitsForAnotherTransactionsLockAtMostTenSeconds() throws Exception {
        SiteInfo site = sites.create(NewSite.of(domain("lock-timeout"), "/", "Before"));
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong failedAfter = new AtomicLong();
        List<Callable<Object>> tasks = List.of(
                () -> {
                    scope.run(site.id(), () -> {
                        options.add("held-" + UUID.randomUUID(), TextNode.valueOf("x"), true);
                        inserted.countDown();
                        try {
                            // longer than the lock timeout; released early once the write has failed
                            release.await(20, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    return "committed";
                },
                () -> {
                    assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
                    long start = System.nanoTime();
                    try {
                        // a change of the domain waits for the open insert on both databases
                        return sites.update(site.id(), SiteChanges.none().withDomain(domain("lock-timeout-moved")));
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
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
        // the network lock was released with the failed write
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> sites.update(site.id(), SiteChanges.none().withName("After")));
    }

    @Test
    void aSitesWriteFromAScopeOfALockHolderIsRefusedQuickly() {
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> {
                    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                                toggles.setNetworkDefault("demo", false);
                                try {
                                    scope.call(MAIN, () -> sites.create(NewSite.of(domain("scoped-create"), "/", "x")));
                                } catch (RuntimeException e) {
                                    throw e;
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            }))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "a network write cannot start inside a transaction that already holds the network lock;"
                                            + " call it outside that transaction");
                }));
    }

    @Test
    void aNetworkWriteFromACompletionCallbackOfALockHolderIsAllowed() {
        String domain = domain("after-completion");
        AtomicReference<Object> outcome = new AtomicReference<>();
        Requests.run(() -> QuarkusTransaction.requiringNew().run(() -> {
            toggles.setNetworkDefault("demo", false);
            registry.registerInterposedSynchronization(new Synchronization() {
                @Override
                public void beforeCompletion() {}

                @Override
                public void afterCompletion(int status) {
                    try {
                        outcome.set(sites.create(NewSite.of(domain, "/", "After")));
                    } catch (Throwable failure) {
                        outcome.set(failure);
                    }
                }
            });
        }));
        assertThat(outcome.get()).isInstanceOf(SiteInfo.class);
        assertThat(at(domain, "/")).isPresent();
    }

    @Test
    void aRollbackOnlyTransactionStillHoldsTheNetworkLock() {
        SiteInfo site = sites.create(NewSite.of(domain("rollback-only"), "/", "R"));
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> Requests.run(() -> {
                    assertThatThrownBy(() -> QuarkusTransaction.requiringNew().run(() -> {
                                toggles.setNetworkDefault("demo", false);
                                QuarkusTransaction.setRollbackOnly();
                                sites.update(site.id(), SiteChanges.none().withName("x"));
                            }))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "a network write cannot start inside a transaction that already holds the network lock;"
                                            + " call it outside that transaction");
                }));
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
    }

    @Test
    void aNetworkWriteInsideATransactionHoldingTheNetworkLockIsRefusedQuickly() {
        SiteInfo site = sites.create(NewSite.of(domain("lock-held"), "/", "Held"));
        List<Runnable> writes = List.of(
                () -> sites.create(NewSite.of(domain("lock-held-new"), "/", "x")),
                () -> sites.update(site.id(), SiteChanges.none().withName("x")),
                () -> sites.setFlag(site.id(), SiteFlag.SPAM, true),
                () -> sites.setPublic(site.id(), 0),
                () -> sites.delete(site.id()));
        for (Runnable write : writes) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(20),
                    () -> Requests.run(() -> {
                        assertThatThrownBy(
                                        () -> QuarkusTransaction.requiringNew().run(() -> {
                                            toggles.setNetworkDefault("demo", false);
                                            write.run();
                                        }))
                                .isInstanceOf(IllegalStateException.class)
                                .hasMessage(
                                        "a network write cannot start inside a transaction that already holds the network"
                                                + " lock; call it outside that transaction");
                    }));
        }
        // the refused transactions rolled back and released the lock; the site is unchanged
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
        assertTimeoutPreemptively(
                Duration.ofSeconds(20),
                () -> sites.update(site.id(), SiteChanges.none().withName("After")));
        assertThat(Requests.call(() -> sites.find(site.id())))
                .map(SiteInfo::name)
                .contains("After");
    }

    @Test
    void featuresReadBeforeTheDefaultsAreSeededDoNotStayCached() {
        Log.readFeaturesOnFlags = true;
        SiteInfo site = sites.create(
                NewSite.of(domain("stale"), "/", "Stale").withPublic(0).withFeatures(Map.of("demo", true)));
        // the flags observer runs before the site's options exist and saw the switch off
        assertThat(Log.flagReads).containsEntry(site.id(), false);
        // the gated creation observer, which runs after, sees the switch on
        assertThat(Log.gated).contains(site.id());
    }

    @Test
    void aGatedCreationObserverReadsTheUncommittedSwitchesInTheCreatingTransaction() {
        SiteInfo site = sites.create(NewSite.of(domain("uncommitted"), "/", "U").withFeatures(Map.of("demo", true)));
        assertThat(Log.views).containsKey(site.id());
        View view = Log.views.get(site.id());
        assertThat(view.enabled()).isTrue();
        assertThat(view.inTransaction()).isTrue();
        assertThat(view.visibleElsewhere()).as("the row is not committed yet").isZero();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_site where id = ?",
                        site.id().value()))
                .isOne();
    }

    @Test
    void aDomainThatNormalizesToNothingOrZeroIsRefused() {
        SiteInfo site = sites.create(NewSite.of(domain("empty-domain"), "/", "E"));
        for (String bad : List.of("", "!!!", " ", "0", " 0 ", "0!")) {
            assertThatThrownBy(() -> sites.create(NewSite.of(bad, "/", "x")))
                    .as("create '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a site domain must not be empty");
            assertThatThrownBy(() -> sites.update(site.id(), SiteChanges.none().withDomain(bad)))
                    .as("update '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a site domain must not be empty");
        }
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
    }

    @Test
    void aPathRequestsCannotReachIsRefused() {
        SiteInfo site = sites.create(NewSite.of(domain("unreachable"), "/", "R"));
        Map<String, String> shown = new java.util.LinkedHashMap<>();
        shown.put("./", "/./");
        shown.put("a/../b", "/a/../b/");
        shown.put("..", "/../");
        shown.put("%2e", "/%2e/");
        shown.put("%2E%2e", "/%2e%2e/");
        shown.put("x/.%2E/y", "/x/.%2e/y/");
        shown.put("0", "/0/");
        shown.put("x/0/y", "/x/0/y/");
        shown.put("a//b", "/a//b/");
        shown.put("a".repeat(70) + "//b", ("/" + "a".repeat(70) + "//b/").substring(0, 64));
        shown.forEach((given, echoed) -> {
            String domain = domain("unreachable-new");
            assertThatThrownBy(() -> sites.create(NewSite.of(domain, given, "x")))
                    .as("create '%s'", given)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(UNREACHABLE.formatted(echoed));
            assertThat(echoed.length()).isLessThanOrEqualTo(64);
            assertThatThrownBy(() -> sites.update(site.id(), SiteChanges.none().withPath(given)))
                    .as("update '%s'", given)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(UNREACHABLE.formatted(echoed));
        });
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
        assertThat(sites.create(NewSite.of(domain("reachable"), "/a.b/0x/%2ex/", "ok"))
                        .path())
                .isEqualTo("/a.b/0x/%2ex/");
    }

    @Test
    void aPathUnderTheNonApplicationRootIsRefused() {
        SiteInfo site = sites.create(NewSite.of(domain("non-app"), "/", "N"));
        for (String path : List.of("q", "/Q/", "q/deep")) {
            String stored = SiteAddresses.normalizePath(path);
            assertThatThrownBy(() -> sites.create(NewSite.of(domain("non-app-new"), path, "x")))
                    .as("create '%s'", path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(NON_APPLICATION.formatted(stored));
            assertThatThrownBy(() -> sites.update(site.id(), SiteChanges.none().withPath(path)))
                    .as("update '%s'", path)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(NON_APPLICATION.formatted(stored));
        }
        assertThat(sites.create(NewSite.of(domain("non-app-ok"), "/qq/", "ok")).path())
                .isEqualTo("/qq/");
        assertThat(Requests.call(() -> sites.find(site.id()))).contains(site);
    }

    @Test
    void aGatedDeletionObserverSeesTheNetworkDefaultsBecauseTheSitesOptionsAreGone() {
        Requests.run(() -> toggles.setNetworkDefault("demo", true));
        try {
            SiteInfo site = sites.create(NewSite.of(domain("gone"), "/", "Gone").withFeatures(Map.of("demo", false)));
            assertThat(Log.gated).doesNotContain(site.id());
            sites.delete(site.id());
            // the site switched the feature off, but its options are deleted before the event: the default applies
            assertThat(Log.deletedGated).containsExactly(site.id());
        } finally {
            Requests.run(() -> toggles.clearNetworkDefault("demo"));
        }
    }

    @Test
    void anAddressChangeWaitsForAnOpenInsertOfTheSitesRowsWithoutDeadlock() throws Exception {
        SiteInfo site = sites.create(NewSite.of(domain("insert-race"), "/", "Before"));
        String option = "held-" + UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String moved = domain("insert-race-moved");
        AtomicLong releasedAt = new AtomicLong();
        AtomicLong updatedAt = new AtomicLong();
        List<Callable<Object>> tasks = List.of(
                () -> {
                    scope.run(site.id(), () -> {
                        options.add(option, TextNode.valueOf("x"), true);
                        inserted.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    return "committed";
                },
                () -> {
                    assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
                    // a change of the domain (a unique index column) waits on both databases
                    SiteInfo result = sites.update(
                            site.id(), SiteChanges.none().withDomain(moved).withName("After"));
                    updatedAt.set(System.nanoTime());
                    return result;
                },
                () -> {
                    inserted.await(30, TimeUnit.SECONDS);
                    Thread.sleep(2000);
                    releasedAt.set(System.nanoTime());
                    release.countDown();
                    return "released";
                });
        List<Object> results = Races.run(tasks);
        assertThat(results).noneMatch(Throwable.class::isInstance);
        assertThat(results.get(0)).isEqualTo("committed");
        // the update waited for the open insert: it returned only after the insert was released and committed
        assertThat(updatedAt.get() - releasedAt.get()).isPositive();
        assertThat(results.get(1)).isInstanceOfSatisfying(SiteInfo.class, updated -> {
            assertThat(updated.name()).isEqualTo("After");
            assertThat(updated.domain()).isEqualTo(moved);
        });
        assertThat(Jdbc.strings(
                        dataSource,
                        "select name from qp_site where id = ?",
                        site.id().value()))
                .containsExactly("After");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_option where site_id = ? and name = ?",
                        site.id().value(),
                        option))
                .isOne();
    }
}

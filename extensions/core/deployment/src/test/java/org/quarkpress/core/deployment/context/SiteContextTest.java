package org.quarkpress.core.deployment.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.FeatureValue;
import org.quarkpress.core.FeatureValue.Source;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.deployment.Requests;
import org.quarkpress.core.deployment.features.TestFeatures;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.RequestOptionCache;
import org.quarkpress.core.runtime.SiteBinding;

class SiteContextTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    Requests.class,
                    TestFeatures.class,
                    Five.class,
                    Directory.class,
                    Store.class,
                    SiteContextTest.class))
            .addBuildChainCustomizer(
                    chain -> TestFeatures.declare(chain, new SiteFeatureBuildItem("demo", "test", true, "Demo")));

    static final SiteId FIVE = SiteId.of(5);
    static final SiteId SIX = SiteId.of(6);
    static final SiteId SEVEN = SiteId.of(7);
    static final SiteId EIGHT = SiteId.of(8);

    @ApplicationScoped
    public static class Five implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return Optional.of(FIVE);
        }
    }

    @ApplicationScoped
    public static class Directory implements SiteDirectory {
        @Override
        public Optional<SiteInfo> find(SiteId site) {
            return site.equals(FIVE)
                    ? Optional.of(new SiteInfo(FIVE, "five.test", "/", "Five", 1, false, false, false, false))
                    : Optional.empty();
        }
    }

    @ApplicationScoped
    public static class Store implements OptionStore {
        final AtomicInteger autoloadReads = new AtomicInteger();
        final AtomicInteger singleReads = new AtomicInteger();
        /** When set, autoloaded() waits here so that a second concurrent reader would meet the first one. */
        volatile CyclicBarrier gate;
        /** A value the store keeps a handle on after handing it out. */
        final ObjectNode shared = JsonNodeFactory.instance.objectNode();

        @Override
        public Optional<JsonNode> find(SiteId site, String name) {
            singleReads.incrementAndGet();
            return name.equals("big_option") ? Optional.of(TextNode.valueOf("big")) : Optional.empty();
        }

        @Override
        public Map<String, JsonNode> autoloaded(SiteId site) {
            autoloadReads.incrementAndGet();
            CyclicBarrier waiting = gate;
            if (waiting != null) {
                try {
                    waiting.await(2, TimeUnit.SECONDS);
                } catch (TimeoutException | BrokenBarrierException alone) {
                    // only one reader came in, as it should
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (site.equals(SIX)) {
                return Map.of("blogname", TextNode.valueOf("Six"));
            }
            if (site.equals(SEVEN)) {
                return Map.of(
                        "gmt_offset",
                        BooleanNode.TRUE,
                        "yes",
                        BooleanNode.TRUE,
                        "no",
                        BooleanNode.FALSE,
                        "ratio",
                        DoubleNode.valueOf(5.0),
                        "half",
                        DoubleNode.valueOf(2.5),
                        "big",
                        LongNode.valueOf(Long.MIN_VALUE));
            }
            if (site.equals(EIGHT)) {
                return Map.of("gmt_offset", BooleanNode.FALSE, "timezone_string", TextNode.valueOf(""));
            }
            ObjectNode settings = JsonNodeFactory.instance.objectNode().put("a", 1);
            return Map.of(
                    "blogname", TextNode.valueOf("My Site"),
                    "timezone_string", TextNode.valueOf("Europe/Paris"),
                    "posts_per_page", IntNode.valueOf(10),
                    "comments_open", TextNode.valueOf("1"),
                    "closed", TextNode.valueOf("0"),
                    "settings", settings,
                    "shared", shared);
        }

        int autoloadReads() {
            return autoloadReads.get();
        }

        int singleReads() {
            return singleReads.get();
        }

        void reset() {
            autoloadReads.set(0);
            singleReads.set(0);
            gate = null;
            shared.removeAll().put("a", 1);
        }
    }

    @Inject
    SiteContext context;

    @Inject
    SiteBinding binding;

    @Inject
    Store store;

    @Inject
    RequestOptionCache cache;

    @BeforeEach
    void reset() {
        store.reset();
    }

    @Test
    void resolvesTheSiteAndItsInfo() {
        Requests.fresh(() -> {
            assertThat(context.isResolved()).isTrue();
            assertThat(context.siteId()).isEqualTo(FIVE);
            assertThat(context.site().name()).isEqualTo("Five");
            assertThat(context.redirect()).isEmpty();
        });
    }

    @Test
    void readsAutoloadedOptionsOncePerRequest() {
        Requests.fresh(() -> {
            assertThat(context.options().string("blogname", "x")).isEqualTo("My Site");
            assertThat(context.options().string(" blogname\n", "x")).isEqualTo("My Site");
            assertThat(context.options().number("posts_per_page", 0)).isEqualTo(10);
            assertThat(store.autoloadReads()).isEqualTo(1);
            assertThat(context.options().get("big_option")).contains(TextNode.valueOf("big"));
            assertThat(context.options().get("big_option")).contains(TextNode.valueOf("big"));
            assertThat(store.singleReads()).isEqualTo(1);
            cache.invalidate(FIVE, "big_option");
            context.options().get("big_option");
            assertThat(store.singleReads()).isEqualTo(2);
        });
    }

    @Test
    void typedAccessorsFallBackWhenTheValueDoesNotFit() {
        Requests.fresh(() -> {
            assertThat(context.options().number("blogname", 7)).isEqualTo(7);
            assertThat(context.options().string("posts_per_page", "")).isEqualTo("10");
            assertThat(context.options().flag("comments_open", false)).isTrue();
            assertThat(context.options().flag("closed", true)).isFalse();
            assertThat(context.options().flag("missing", true)).isTrue();
        });
    }

    @Test
    void theZoneComesFromTheOptions() {
        Requests.fresh(() -> assertThat(context.zone()).isEqualTo(ZoneId.of("Europe/Paris")));
    }

    @Test
    void theNetworkScopeHasNoCurrentSite() {
        Requests.fresh(() -> {
            binding.bind(Binding.NETWORK);
            assertThat(context.isResolved()).isFalse();
            assertThatThrownBy(() -> context.siteId())
                    .isInstanceOf(SiteNotResolvedException.class)
                    .hasMessageContaining("network");
            assertThatThrownBy(() -> context.options()).isInstanceOf(SiteNotResolvedException.class);
            assertThatThrownBy(() -> context.site()).isInstanceOf(SiteNotResolvedException.class);
            assertThatThrownBy(() -> context.zone()).isInstanceOf(SiteNotResolvedException.class);
            assertThat(context.features().all()).containsExactly(new FeatureValue("demo", false, Source.NO_SITE));
            assertThat(store.autoloadReads()).isZero();
        });
    }

    @Test
    void anUnresolvedRequestHasNoCurrentSite() {
        Requests.fresh(() -> {
            binding.bind(Binding.UNRESOLVED);
            assertThat(context.isResolved()).isFalse();
            assertThatThrownBy(() -> context.siteId())
                    .isInstanceOf(SiteNotResolvedException.class)
                    .hasMessageContaining("no site is resolved");
            assertThatThrownBy(() -> context.site()).isInstanceOf(SiteNotResolvedException.class);
            assertThatThrownBy(() -> context.options()).isInstanceOf(SiteNotResolvedException.class);
            assertThatThrownBy(() -> context.zone()).isInstanceOf(SiteNotResolvedException.class);
            assertThat(context.features().all()).containsExactly(new FeatureValue("demo", false, Source.NO_SITE));
            assertThat(store.autoloadReads()).isZero();
        });
    }

    @Test
    void aBooleanOffsetIsReadAsPhpCastsItToAFloat() {
        Requests.fresh(() -> {
            binding.bind(Binding.site(SEVEN));
            assertThat(context.zone()).isEqualTo(ZoneOffset.ofHours(1));
        });
        Requests.fresh(() -> {
            binding.bind(Binding.site(EIGHT));
            assertThat(context.zone()).isEqualTo(ZoneOffset.UTC);
        });
    }

    @Test
    void stringsAreWrittenAsPhpWritesThem() {
        Requests.fresh(() -> {
            binding.bind(Binding.site(SEVEN));
            assertThat(context.options().string("yes", "x")).isEqualTo("1");
            assertThat(context.options().string("no", "x")).isEmpty();
            assertThat(context.options().string("ratio", "x")).isEqualTo("5");
            assertThat(context.options().string("half", "x")).isEqualTo("2.5");
            assertThat(context.options().string("big", "x")).isEqualTo("-9223372036854775808");
            assertThat(context.options().string("missing", "x")).isEqualTo("x");
        });
    }

    @Test
    void aMissingSiteIsReported() {
        Requests.fresh(() -> {
            binding.bind(Binding.site(SIX));
            assertThatThrownBy(() -> context.site())
                    .isInstanceOf(SiteNotResolvedException.class)
                    .hasMessageContaining("does not exist");
        });
    }

    @Test
    void twoSitesInOneRequestDoNotShareOptions() {
        Requests.fresh(() -> {
            assertThat(cache.get(FIVE, "blogname")).contains(TextNode.valueOf("My Site"));
            assertThat(cache.get(SIX, "blogname")).contains(TextNode.valueOf("Six"));
            assertThat(cache.get(SIX, "posts_per_page")).isEmpty();
            assertThat(cache.get(FIVE, "blogname")).contains(TextNode.valueOf("My Site"));
            assertThat(store.autoloadReads()).isEqualTo(2);
            cache.invalidate(SIX, "blogname");
            assertThat(cache.get(FIVE, "blogname")).contains(TextNode.valueOf("My Site"));
            assertThat(store.autoloadReads()).isEqualTo(2);
            assertThat(cache.get(SIX, "blogname")).contains(TextNode.valueOf("Six"));
            assertThat(store.autoloadReads()).isEqualTo(3);
        });
    }

    @Test
    void invalidatingAnAutoloadedOptionRereadsTheAutoloadedSet() {
        Requests.fresh(() -> {
            context.options().get("blogname");
            cache.invalidate(FIVE, "blogname");
            context.options().get("blogname");
            assertThat(store.autoloadReads()).isEqualTo(2);
        });
    }

    @Test
    void returnedValuesAreCopies() {
        Requests.fresh(() -> {
            ObjectNode first = (ObjectNode) context.options().get("settings").orElseThrow();
            first.put("a", 2);
            assertThat(context.options().get("settings").orElseThrow().get("a").intValue())
                    .isEqualTo(1);
        });
    }

    @Test
    void theCacheKeepsItsOwnCopyOfWhatTheStoreReturned() {
        Requests.fresh(() -> {
            assertThat(context.options().get("shared").orElseThrow().get("a").intValue())
                    .isEqualTo(1);
            store.shared.put("a", 9);
            assertThat(context.options().get("shared").orElseThrow().get("a").intValue())
                    .isEqualTo(1);
        });
    }

    @Test
    void threadsSharingARequestContextReadTheStoreOnce() {
        int threads = 8;
        store.gate = new CyclicBarrier(2);
        Requests.fresh(() -> {
            ManagedContext requestContext = Arc.container().requestContext();
            var state = requestContext.getState();
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<List<Optional<JsonNode>>>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        requestContext.activate(state);
                        try {
                            go.await();
                            return List.of(
                                    context.options().get("blogname"),
                                    context.options().get("big_option"));
                        } finally {
                            requestContext.deactivate();
                        }
                    }));
                }
                go.countDown();
                for (Future<List<Optional<JsonNode>>> result : results) {
                    assertThat(result.get())
                            .containsExactly(
                                    Optional.of(TextNode.valueOf("My Site")), Optional.of(TextNode.valueOf("big")));
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                pool.shutdownNow();
            }
            assertThat(store.autoloadReads()).isEqualTo(1);
            assertThat(store.singleReads()).isEqualTo(1);
        });
    }
}

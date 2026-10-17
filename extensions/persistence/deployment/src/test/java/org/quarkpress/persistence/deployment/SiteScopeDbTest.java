package org.quarkpress.persistence.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.hostcode.NativeLoader;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Status;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;
import org.quarkpress.persistence.runtime.TenantIds;

class SiteScopeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = DbApp.with(SiteScopeDbTest.class, NativeLoader.class);

    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");

    @Inject
    SiteScope scope;

    @Inject
    OptionRepository options;

    @Inject
    ManagedExecutor executor;

    @Inject
    NativeLoader nativeLoader;

    @Inject
    SiteBinding binding;

    @Inject
    SiteContext context;

    @Inject
    TestSites sites;

    @Inject
    Vertx vertx;

    private void put(SiteId site, String name) {
        scope.run(site, () -> options.persist(OptionEntity.create(site, name, TextNode.valueOf(name), true, AT)));
    }

    private <T> T in(SiteId site, Callable<T> work) {
        try {
            return scope.call(site, work);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private boolean stored(SiteId site, String name) {
        return in(site, () -> options.findOption(site, name).isPresent());
    }

    @Test
    void aSiteSeesOnlyItsOwnRows() {
        SiteId a = sites.create("iso-a");
        SiteId b = sites.create("iso-b");
        String name = "iso-" + UUID.randomUUID();
        put(a, name);
        assertThat(stored(a, name)).isTrue();
        assertThat(in(b, () -> options.findOption(a, name).isPresent())).isFalse();
        assertThat(in(b, () -> options.count("name", name))).isZero();
    }

    @Test
    void theOuterBindingIsRestored() {
        SiteId a = sites.create("restore-a");
        SiteId b = sites.create("restore-b");
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            scope.run(b, () -> assertThat(context.siteId()).isEqualTo(b));
            assertThat(context.siteId()).isEqualTo(a);
        });
    }

    @Test
    void nestedScopeForAnotherSiteCommitsIndependently() {
        SiteId a = sites.create("nested-a");
        SiteId b = sites.create("nested-b");
        String tag = UUID.randomUUID().toString();
        assertThatThrownBy(() -> scope.run(a, () -> {
                    options.persist(OptionEntity.create(a, "outer-" + tag, TextNode.valueOf("o"), true, AT));
                    scope.run(
                            b,
                            () -> options.persist(
                                    OptionEntity.create(b, "inner-" + tag, TextNode.valueOf("i"), true, AT)));
                    throw new IllegalStateException("roll back the outer work");
                }))
                .hasMessageContaining("roll back the outer work");
        assertThat(stored(a, "outer-" + tag)).isFalse();
        assertThat(stored(b, "inner-" + tag)).isTrue();
    }

    @Test
    void nestedScopeForTheSameSiteJoinsTheTransaction() {
        SiteId a = sites.create("join-a");
        String tag = UUID.randomUUID().toString();
        assertThatThrownBy(() -> scope.run(a, () -> {
                    options.persist(OptionEntity.create(a, "outer-" + tag, TextNode.valueOf("o"), true, AT));
                    scope.run(
                            a,
                            () -> options.persist(
                                    OptionEntity.create(a, "inner-" + tag, TextNode.valueOf("i"), true, AT)));
                    throw new IllegalStateException("roll back everything");
                }))
                .hasMessageContaining("roll back everything");
        assertThat(stored(a, "outer-" + tag)).isFalse();
        assertThat(stored(a, "inner-" + tag)).isFalse();
    }

    @Test
    void threadWithoutRequestContextFailsClosed() {
        SiteId a = sites.create("closed-a");
        put(a, "closed-" + UUID.randomUUID());
        AtomicReference<Object> outcome = new AtomicReference<>();
        Requests.outside(() -> {
            try {
                outcome.set(QuarkusTransaction.requiringNew().call(() -> options.count()));
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        });
        assertThat(outcome.get())
                .isInstanceOfSatisfying(
                        Throwable.class,
                        failure -> assertThat(Failures.messages(failure)).contains("no request context"));
    }

    @Test
    void tasksThatCapturedTheCallersContextKeepTheCallersSite() {
        SiteId a = sites.create("prop-a");
        SiteId b = sites.create("prop-b");
        AtomicReference<Binding> seenByTask = new AtomicReference<>();
        AtomicReference<Binding> seenInScope = new AtomicReference<>();
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            CountDownLatch scopeEntered = new CountDownLatch(1);
            CompletableFuture<Void> task = executor.runAsync(() -> {
                try {
                    scopeEntered.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                seenByTask.set(binding.current());
            });
            scope.run(b, () -> {
                seenInScope.set(binding.current());
                scopeEntered.countDown();
                task.join();
            });
            assertThat(binding.current()).isEqualTo(Binding.site(a));
        });
        assertThat(seenInScope.get()).isEqualTo(Binding.site(b));
        assertThat(seenByTask.get()).isEqualTo(Binding.site(a));
    }

    @Test
    void nothingIsWrittenWhileNoSiteIsBound() {
        SiteId a = sites.create("unbound-a");
        Requests.fresh(() -> {
            binding.bind(Binding.UNRESOLVED);
            assertThatThrownBy(() -> QuarkusTransaction.requiringNew()
                            .run(() -> options.persist(OptionEntity.create(
                                    a, "unbound-" + UUID.randomUUID(), TextNode.valueOf("x"), true, AT))))
                    .satisfies(failure -> assertThat(Failures.messages(failure)).contains("no site is bound"));
        });
    }

    @Test
    void aRowLoadedAroundTheFilterCannotBeWrittenFromAnotherSite() {
        SiteId a = sites.create("native-a");
        SiteId b = sites.create("native-b");
        String name = "native-" + UUID.randomUUID();
        put(a, name);
        long id = in(a, () -> options.findOption(a, name).orElseThrow().id);
        assertThatThrownBy(
                        () -> scope.run(b, () -> nativeLoader.loadAroundTheFilterAndChange(id, TextNode.valueOf("x"))))
                .satisfies(failure -> assertThat(Failures.messages(failure)).contains("changed under site"));
        assertThat(in(a, () -> options.findOption(a, name).orElseThrow().value)).isEqualTo(TextNode.valueOf(name));
    }

    @Test
    void aDetachedRowOfAnotherSiteCannotBeMergedIn() {
        SiteId a = sites.create("merge-a");
        SiteId b = sites.create("merge-b");
        String name = "merge-" + UUID.randomUUID();
        put(a, name);
        long id = in(a, () -> options.findOption(a, name).orElseThrow().id);
        assertThatThrownBy(() -> scope.run(b, () -> {
                    OptionEntity detached = OptionEntity.create(a, name, TextNode.valueOf("hijacked"), true, AT);
                    detached.id = id;
                    options.getEntityManager().merge(detached);
                    options.flush();
                }))
                .isInstanceOf(OptimisticLockException.class);
        assertThat(in(a, () -> options.findOption(a, name).orElseThrow().value)).isEqualTo(TextNode.valueOf(name));
        assertThat(in(b, () -> options.count("name", name))).isZero();
    }

    @Test
    void bulkStatementsThatNameTheSiteTouchOnlyThatSite() {
        SiteId a = sites.create("bulk-a");
        SiteId b = sites.create("bulk-b");
        String name = "bulk-" + UUID.randomUUID();
        put(a, name);
        put(b, name);
        assertThat(in(
                        a,
                        () -> options.update(
                                "autoload = false where siteId = ?1 and name = ?2", TenantIds.param(a), name)))
                .isEqualTo(1);
        assertThat(in(b, () -> options.findOption(b, name).orElseThrow().autoload))
                .isTrue();
        assertThat(in(a, () -> options.delete("siteId = ?1 and name = ?2", TenantIds.param(a), name)))
                .isEqualTo(1);
        assertThat(stored(b, name)).isTrue();
        assertThat(stored(a, name)).isFalse();
    }

    @Test
    void concurrentScopesStayIsolated() throws Exception {
        SiteId a = sites.create("conc-a");
        SiteId b = sites.create("conc-b");
        String tag = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                SiteId own = i % 2 == 0 ? a : b;
                SiteId other = i % 2 == 0 ? b : a;
                String name = tag + "-" + i;
                results.add(pool.submit(() -> scope.call(own, () -> {
                    options.persist(OptionEntity.create(own, name, TextNode.valueOf(name), true, AT));
                    options.flush();
                    long foreign = options.count("siteId = ?1", TenantIds.param(other));
                    long mine = options.count("siteId = ?1 and name = ?2", TenantIds.param(own), name);
                    return foreign == 0 && mine == 1 ? "ok" : "foreign=" + foreign + " mine=" + mine;
                })));
            }
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("ok");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void checkedExceptionsPassThroughUnchanged() {
        SiteId a = sites.create("checked-a");
        IOException original = new IOException("disk on fire");
        assertThatThrownBy(() -> scope.call(a, () -> {
                    throw original;
                }))
                .isSameAs(original);
    }

    @Test
    void aScopeLeavesAnUnboundCallerUnbound() {
        SiteId a = sites.create("unbound-caller-a");
        SiteId b = sites.create("unbound-caller-b");
        AtomicReference<Binding> seenInScope = new AtomicReference<>();
        Requests.fresh(() -> {
            scope.run(b, () -> seenInScope.set(binding.current()));
            assertThat(binding.bound()).isEmpty();
            binding.bind(Binding.site(a));
            assertThat(binding.current()).isEqualTo(Binding.site(a));
        });
        assertThat(seenInScope.get()).isEqualTo(Binding.site(b));
    }

    @Test
    void theCallerIsRestoredAfterTheWorkThrows() {
        SiteId a = sites.create("throw-a");
        SiteId b = sites.create("throw-b");
        Requests.fresh(() -> {
            binding.bind(Binding.site(a));
            assertThatThrownBy(() -> scope.run(b, () -> {
                        throw new IllegalArgumentException("boom");
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("boom");
            assertThat(binding.current()).isEqualTo(Binding.site(a));
            assertThatThrownBy(() -> scope.run(b, () -> {
                        throw new AssertionError("error");
                    }))
                    .isInstanceOf(AssertionError.class)
                    .hasMessage("error");
            assertThat(binding.current()).isEqualTo(Binding.site(a));
            assertThat(Arc.container().requestContext().isActive()).isTrue();
        });
    }

    @Test
    void anInnerRollbackLeavesTheOuterTransactionToCommit() {
        SiteId a = sites.create("inner-rb-a");
        SiteId b = sites.create("inner-rb-b");
        String tag = UUID.randomUUID().toString();
        AtomicReference<Integer> outerStatus = new AtomicReference<>();
        scope.run(a, () -> {
            options.persist(OptionEntity.create(a, "x-" + tag, TextNode.valueOf("x"), true, AT));
            options.flush();
            assertThatThrownBy(() -> scope.run(b, () -> {
                        options.persist(OptionEntity.create(b, "inner-" + tag, TextNode.valueOf("i"), true, AT));
                        options.flush();
                        throw new IllegalStateException("inner rollback");
                    }))
                    .hasMessage("inner rollback");
            outerStatus.set(QuarkusTransaction.getStatus());
            options.persist(OptionEntity.create(a, "y-" + tag, TextNode.valueOf("y"), true, AT));
        });
        assertThat(outerStatus.get()).isEqualTo(Status.STATUS_ACTIVE);
        assertThat(stored(a, "x-" + tag)).isTrue();
        assertThat(stored(a, "y-" + tag)).isTrue();
        assertThat(stored(b, "inner-" + tag)).isFalse();
    }

    @Test
    void otherThreadsOnTheSameVertxContextKeepTheCallersSite() throws Exception {
        SiteId a = sites.create("vertx-a");
        SiteId b = sites.create("vertx-b");
        Context duplicated = VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch observed = new CountDownLatch(1);
        AtomicReference<Binding> seenInScope = new AtomicReference<>();
        // Two blocking handlers of one request: they run on two worker threads that share the duplicated context,
        // and so the request context the first one activates.
        CompletableFuture<Binding> caller = duplicated
                .executeBlocking(
                        () -> {
                            var requestContext = Arc.container().requestContext();
                            requestContext.activate();
                            try {
                                binding.bind(Binding.site(a));
                                scope.run(b, () -> {
                                    seenInScope.set(binding.current());
                                    entered.countDown();
                                    try {
                                        observed.await(30, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
                                return binding.current();
                            } finally {
                                requestContext.terminate();
                            }
                        },
                        false)
                .toCompletionStage()
                .toCompletableFuture();
        CompletableFuture<Binding> other = duplicated
                .executeBlocking(
                        () -> {
                            try {
                                entered.await(30, TimeUnit.SECONDS);
                                return Arc.container()
                                        .instance(SiteBinding.class)
                                        .get()
                                        .current();
                            } finally {
                                observed.countDown();
                            }
                        },
                        false)
                .toCompletionStage()
                .toCompletableFuture();
        assertThat(other.get(60, TimeUnit.SECONDS)).isEqualTo(Binding.site(a));
        assertThat(caller.get(60, TimeUnit.SECONDS)).isEqualTo(Binding.site(a));
        assertThat(seenInScope.get()).isEqualTo(Binding.site(b));
    }

    @Test
    void aScopeIsRefusedOnAnEventLoopThread() throws Exception {
        SiteId a = sites.create("loop-a");
        CompletableFuture<Throwable> outcome = new CompletableFuture<>();
        VertxContext.createNewDuplicatedContext(vertx.getOrCreateContext()).runOnContext(ignored -> {
            try {
                scope.run(a, () -> {});
                outcome.complete(null);
            } catch (Throwable failure) {
                outcome.complete(failure);
            }
        });
        assertThat(outcome.get(30, TimeUnit.SECONDS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a site scope cannot run on an event-loop thread");
    }

    @Test
    void aSiteIsRequired() {
        assertThatThrownBy(() -> scope.run(null, () -> {})).isInstanceOf(NullPointerException.class);
    }
}

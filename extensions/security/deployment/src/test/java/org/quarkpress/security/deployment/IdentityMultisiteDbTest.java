package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableContext;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.vertx.VertxContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.SiteResolver;
import org.quarkpress.core.UserId;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.security.CapCheck;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserCapabilitiesFilter;

/**
 * Identities in a network, where the request's site, no site and the main site give different answers: the roles are
 * the user's role keys on the request's site, and a permission check made on an event-loop thread inside a request
 * context answers for the binding the request already has, never resolves one there, reads its data on a worker thread
 * and delivers its answer back on the caller's Vert.x context.
 */
class IdentityMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(
                    IdentityMultisiteDbTest.class, TestResolver.class, ThreadRecorder.class)
            .overrideConfigKey("quarkpress.multisite.enabled", "true");

    /** Resolves every request to {@link #SITE}, when set. */
    @ApplicationScoped
    public static class TestResolver implements SiteResolver {
        static final AtomicReference<SiteId> SITE = new AtomicReference<>();

        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return Optional.ofNullable(SITE.get());
        }
    }

    /** Records whether checks read their data on an event-loop thread. */
    @ApplicationScoped
    public static class ThreadRecorder implements UserCapabilitiesFilter {
        static final List<Boolean> ON_EVENT_LOOP = new CopyOnWriteArrayList<>();

        @Override
        public Map<String, Boolean> apply(Map<String, Boolean> caps, CapCheck check) {
            ON_EVENT_LOOP.add(Context.isOnEventLoopThread());
            return caps;
        }
    }

    @Inject
    Vertx vertx;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    static final SiteId MAIN = SiteId.of(1);

    /** An identity of a new subject, augmented once (outside any request) so that its profile exists. */
    private SecurityIdentity signedIn(String subject) {
        return Identities.augment(Identities.jwt(subject, null, null, null));
    }

    /** {@code identity} augmented inside a request context whose binding the test resolver decides. */
    private static SecurityIdentity augmentedInRequest(SecurityIdentity identity) {
        return Requests.call(() -> {
            Arc.container().instance(SiteBinding.class).get().current();
            return Identities.augment(identity);
        });
    }

    @Test
    void rolesAreTheRoleKeysOnTheSiteTheRequestIsBoundTo() {
        SiteId site = TestData.site(dataSource);
        String subject = "sub-" + UUID.randomUUID();
        UserId user = QuarkPressPrincipal.of(signedIn(subject)).orElseThrow().user();
        TestData.member(dataSource, site, user, "[\"editor\"]", "{}");
        TestData.member(dataSource, MAIN, user, "[\"subscriber\"]", "{}");
        try {
            TestResolver.SITE.set(site);
            assertThat(augmentedInRequest(Identities.jwt(subject, null, null, null))
                            .getRoles())
                    .containsExactly("editor");
            TestResolver.SITE.set(MAIN);
            assertThat(augmentedInRequest(Identities.jwt(subject, null, null, null))
                            .getRoles())
                    .containsExactly("subscriber");
        } finally {
            TestResolver.SITE.set(null);
        }
    }

    @Test
    void anUnresolvedRequestGivesNoRoles() {
        String subject = "sub-" + UUID.randomUUID();
        UserId user = QuarkPressPrincipal.of(signedIn(subject)).orElseThrow().user();
        TestData.member(dataSource, MAIN, user, "[\"editor\"]", "{}");
        SecurityIdentity inRequest = augmentedInRequest(Identities.jwt(subject, null, null, null));
        assertThat(QuarkPressPrincipal.of(inRequest)).isPresent();
        assertThat(inRequest.getRoles()).isEmpty();
        assertThat(Identities.augment(Identities.jwt(subject, null, null, null)).getRoles())
                .isEmpty();
    }

    @Test
    void aCheckOnTheEventLoopAnswersForTheBindingTheRequestAlreadyHas() throws Exception {
        SiteId site = TestData.site(dataSource);
        SecurityIdentity identity = Identities.augment(Identities.jwt("sub-" + UUID.randomUUID(), null, null, null));
        TestData.member(
                dataSource, site, QuarkPressPrincipal.of(identity).orElseThrow().user(), "[\"author\"]", "{}");
        TestResolver.SITE.set(site);
        ManagedContext requests = Arc.container().requestContext();
        requests.activate();
        InjectableContext.ContextState request = requests.getState();
        requests.deactivate();
        try {
            ThreadRecorder.ON_EVENT_LOOP.clear();
            // not bound yet: the event loop does not resolve, so the check is for no site
            assertThat(checkOnEventLoop(identity, request, "publish_posts")).isFalse();
            assertThat(checkOnEventLoop(identity, request, "exist")).isTrue();
            // the request is bound (on another thread of the same request), then checked again on the event loop
            requests.activate(request);
            try {
                Arc.container().instance(SiteBinding.class).get().current();
            } finally {
                requests.deactivate();
            }
            assertThat(checkOnEventLoop(identity, request, "publish_posts")).isTrue();
            assertThat(checkOnEventLoop(identity, request, "edit_others_posts")).isFalse();
            assertThat(ThreadRecorder.ON_EVENT_LOOP).isNotEmpty().containsOnly(false);
        } finally {
            TestResolver.SITE.set(null);
            requests.activate(request);
            requests.terminate();
        }
    }

    /**
     * Checks {@code capability} on an event-loop thread, on a duplicated Vert.x context (as Quarkus serves a request)
     * with {@code request} active there. Fails unless the answer is delivered on that same context.
     */
    private boolean checkOnEventLoop(
            SecurityIdentity identity, InjectableContext.ContextState request, String capability) throws Exception {
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        vertx.runOnContext(ignored -> {
            Context caller = VertxContext.getOrCreateDuplicatedContext(vertx);
            caller.runOnContext(alsoIgnored -> {
                ManagedContext requests = Arc.container().requestContext();
                requests.activate(request);
                try {
                    identity.checkPermission(new StringPermission(capability))
                            .subscribe()
                            .with(
                                    granted -> {
                                        if (Vertx.currentContext() == caller) {
                                            answer.complete(granted);
                                        } else {
                                            answer.completeExceptionally(new AssertionError("the answer for "
                                                    + capability + " came on "
                                                    + Thread.currentThread().getName()
                                                    + " with context " + Vertx.currentContext()
                                                    + ", not on the caller's context"));
                                        }
                                    },
                                    answer::completeExceptionally);
                } catch (RuntimeException e) {
                    answer.completeExceptionally(e);
                } finally {
                    requests.deactivate();
                }
            });
        });
        return answer.get(30, TimeUnit.SECONDS);
    }
}

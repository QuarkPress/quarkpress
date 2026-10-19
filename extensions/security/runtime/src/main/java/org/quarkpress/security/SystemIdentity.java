package org.quarkpress.security;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Callable;
import org.quarkpress.security.runtime.IdentityPermissions;

/**
 * The identity of work no user asked for: scheduled publishing, imports run by the system, startup tasks. It holds
 * every capability except {@code unfiltered_html} (content it saves is filtered, as WordPress filters what its cron
 * saves) and never passes a check that maps to {@code do_not_allow}; it has no user, so user-editing checks refuse it.
 * It is never derived from a token.
 *
 * <p>The mark is an attribute whose value is an object private to this class, so an identity cannot be made the system
 * from a token or by setting an attribute of that name to any value. It is not a secret from code inside the
 * application: anyone holding the system identity (this bean, or code that sees the identity during system work) can
 * copy it, its mark and its permission checker onto another identity.
 *
 * <p>{@link #run} and {@link #call} put it on the current request context for the duration of the work (activating a
 * context when none is active) and restore the previous identity afterwards, also when the work throws. They refuse on
 * a Vert.x event-loop thread, when the current request context belongs to an HTTP request, and when an authenticated
 * identity is current. These refusals are a tripwire against misuse on the calling thread, not a boundary: a plain
 * Vert.x router handler on a worker, a websocket handler, or work that hops to another thread or into a site scope
 * (which gets a fresh request context) is not recognised as serving a request. A service that acts as the
 * system for a caller must check the caller's capability first (for example with {@code CapabilityGuard}) on the
 * caller's thread, before it hops.
 */
@ApplicationScoped
public class SystemIdentity {

    /** The principal name of the system identity. */
    public static final String NAME = "quarkpress-system";

    /** The name of the attribute that carries the mark. */
    private static final String ATTRIBUTE = "org.quarkpress.security.system";

    /** The mark: obtainable outside this class only from the system identity itself (or a copy of it). */
    private static final Object MARK = new Object() {
        @Override
        public String toString() {
            return "the QuarkPress system identity";
        }
    };

    @Inject
    IdentityPermissions permissions;

    private SecurityIdentity identity;

    @PostConstruct
    void build() {
        identity = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(NAME))
                .addAttribute(ATTRIBUTE, MARK)
                .addPermissionChecker(permissions.systemChecker())
                .build();
    }

    /** The system identity. */
    public SecurityIdentity identity() {
        return identity;
    }

    /** Whether {@code identity} is the system identity (or a copy of it). */
    public static boolean isSystem(SecurityIdentity identity) {
        return identity != null && identity.getAttribute(ATTRIBUTE) == MARK;
    }

    /**
     * Runs {@code work} as the system and returns its result.
     *
     * @throws IllegalStateException on a Vert.x event-loop thread, inside an HTTP request seen by the current request
     *     context, or when the current identity is an authenticated one other than the system
     */
    public <T> T call(Callable<T> work) throws Exception {
        if (io.vertx.core.Context.isOnEventLoopThread()) {
            throw new IllegalStateException("the system identity cannot run on an event-loop thread");
        }
        ManagedContext requests = Arc.container().requestContext();
        boolean activated = !requests.isActive();
        if (activated) {
            requests.activate();
        }
        try {
            CurrentVertxRequest http =
                    Arc.container().instance(CurrentVertxRequest.class).get();
            if (http.getCurrent() != null) {
                throw new IllegalStateException("the system identity cannot run inside an HTTP request");
            }
            CurrentIdentityAssociation association =
                    Arc.container().instance(CurrentIdentityAssociation.class).get();
            SecurityIdentity previous = association.getIdentity();
            if (!previous.isAnonymous() && !isSystem(previous)) {
                throw new IllegalStateException("the system identity runs only where nobody is authenticated; the"
                        + " identity is " + previous.getPrincipal().getName());
            }
            association.setIdentity(identity());
            try {
                return work.call();
            } finally {
                association.setIdentity(previous);
            }
        } finally {
            if (activated) {
                requests.terminate();
            }
        }
    }

    /** Runs {@code work} as the system; see {@link #call}. */
    public void run(Runnable work) {
        try {
            call(() -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

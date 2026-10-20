package org.quarkpress.security.runtime;

import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.spi.runtime.BlockingSecurityExecutor;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Permission;
import java.util.Optional;
import java.util.function.Function;
import org.quarkpress.core.SiteId;

/**
 * The permission checker QuarkPress adds to identities: a {@code StringPermission} whose name is a capability (and that
 * has no actions) is granted when the actor has the capability for the binding in effect when it is checked. The site
 * is taken on the calling thread; on an event-loop thread the database reads run on a worker thread instead, through
 * Quarkus's blocking security executor, which delivers the answer back on the caller's Vert.x context.
 *
 * <p>{@link #systemChecker()} is public only so that {@code SystemIdentity} (in another package) can build its
 * identity; it protects nothing: whoever attaches it to an identity makes that identity's permission checks answer
 * as the system. Application code has no business calling it.
 */
@ApplicationScoped
public class IdentityPermissions {

    @Inject
    CapabilityEvaluator evaluator;

    @Inject
    CheckSite checkSite;

    @Inject
    BlockingSecurityExecutor blocking;

    /** The checker of the system identity; for {@code SystemIdentity} only. */
    public Function<Permission, Uni<Boolean>> systemChecker() {
        return checker(Actor.SYSTEM);
    }

    /** Whether {@code permission} names a capability: a {@code StringPermission} without actions. */
    static boolean isCapability(Permission permission) {
        return permission instanceof StringPermission named && named.getActions() == null;
    }

    /**
     * {@code identity}'s own permission checks, except that every capability is refused: the permissions a mechanism
     * gave the identity (Quarkus OIDC turns each token scope into a {@code StringPermission} without actions) never
     * answer a capability, which only QuarkPress decides. Other permissions answer as before.
     */
    static Function<Permission, Uni<Boolean>> withoutCapabilities(SecurityIdentity identity) {
        return permission ->
                isCapability(permission) ? Uni.createFrom().item(false) : identity.checkPermission(permission);
    }

    /** The checker for {@code actor}. */
    Function<Permission, Uni<Boolean>> checker(Actor actor) {
        return permission -> {
            if (!isCapability(permission)) {
                return Uni.createFrom().item(false);
            }
            StringPermission named = (StringPermission) permission;
            Optional<SiteId> site = checkSite.current();
            if (io.vertx.core.Context.isOnEventLoopThread()) {
                return blocking.executeBlocking(() -> evaluator.check(actor, site, named.getName(), Optional.empty()));
            }
            return Uni.createFrom().item(evaluator.check(actor, site, named.getName(), Optional.empty()));
        };
    }
}

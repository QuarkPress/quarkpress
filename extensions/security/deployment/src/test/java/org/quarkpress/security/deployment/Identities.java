package org.quarkpress.security.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.arc.ManagedContext;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPermissionSecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.quarkpress.core.UserId;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.runtime.QuarkPressIdentityAugmentor;

/** Identities for tests, and running code as one of them in a request context of its own. */
public final class Identities {

    private Identities() {}

    /** Runs blocking work in place, as Quarkus runs it on a worker thread. */
    private static final AuthenticationRequestContext IN_PLACE =
            supplier -> Uni.createFrom().item(supplier);

    /**
     * {@code identity} as the application's identity building leaves it: every {@link SecurityIdentityAugmentor} bean
     * applied in turn, highest {@code priority()} first, as Quarkus's identity provider manager applies them. This
     * includes QuarkPress's augmentor (profile provisioned, principal, roles, capability checker) and Quarkus's own
     * augmentor that grants {@code @PermissionChecker} permissions, which Quarkus always applies last whatever its
     * priority (it wraps the finished identity), so this does too.
     */
    public static SecurityIdentity augment(SecurityIdentity identity) {
        List<SecurityIdentityAugmentor> augmentors = new ArrayList<>();
        for (InstanceHandle<SecurityIdentityAugmentor> handle :
                Arc.container().listAll(SecurityIdentityAugmentor.class)) {
            augmentors.add(handle.get());
        }
        augmentors.sort(Comparator.<SecurityIdentityAugmentor, Boolean>comparing(
                        augmentor -> augmentor instanceof QuarkusPermissionSecurityIdentityAugmentor)
                .thenComparing(Comparator.comparingInt(SecurityIdentityAugmentor::priority)
                        .reversed()));
        SecurityIdentity current = identity;
        for (SecurityIdentityAugmentor augmentor : augmentors) {
            current = augmentor.augment(current, IN_PLACE, Map.of()).await().indefinitely();
        }
        return current;
    }

    /** {@code identity} after QuarkPress's augmentor only, for tests of that augmentor itself. */
    public static SecurityIdentity augmentByQuarkPress(SecurityIdentity identity) {
        return Arc.container()
                .instance(QuarkPressIdentityAugmentor.class)
                .get()
                .augment(identity, IN_PLACE)
                .await()
                .indefinitely();
    }

    /** An authenticated identity whose principal is only a name (no token). */
    public static SecurityIdentity named(String subject) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .build();
    }

    /** An authenticated identity whose principal is a token with these claims (null claims left out). */
    public static SecurityIdentity jwt(String subject, String preferredUsername, String givenName, String familyName) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", subject);
        if (preferredUsername != null) {
            claims.put("preferred_username", preferredUsername);
        }
        if (givenName != null) {
            claims.put("given_name", givenName);
        }
        if (familyName != null) {
            claims.put("family_name", familyName);
        }
        return token(claims, Set.of());
    }

    /**
     * An authenticated identity whose principal is a token with exactly {@code claims}, and whose roles are
     * {@code roles}, as an OIDC mechanism leaves it after reading the token's groups or realm roles.
     */
    public static SecurityIdentity token(Map<String, Object> claims, Set<String> roles) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new TestJwt(claims))
                .addRoles(roles)
                .build();
    }

    /** An identity of a profile, as the augmentor leaves it, without permission checker (enough for explicit checks). */
    public static SecurityIdentity user(UserId user, String subject) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(subject))
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(user, subject))
                .build();
    }

    /** The application's system identity ({@link SystemIdentity#identity()}). */
    public static SecurityIdentity system() {
        return Arc.container().instance(SystemIdentity.class).get().identity();
    }

    /** Runs {@code work} on the current thread in a fresh request context whose identity is {@code identity}. */
    public static <T> T as(SecurityIdentity identity, Supplier<T> work) {
        ManagedContext requests = Arc.container().requestContext();
        if (requests.isActive()) {
            throw new IllegalStateException("Identities.as needs a thread without a request context");
        }
        requests.activate();
        try {
            Arc.container().instance(CurrentIdentityAssociation.class).get().setIdentity(identity);
            return work.get();
        } finally {
            requests.terminate();
        }
    }
}

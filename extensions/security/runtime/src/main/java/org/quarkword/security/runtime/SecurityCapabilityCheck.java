package org.quarkword.security.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.CapabilityCheck;
import org.quarkword.core.CapabilityDeniedException;
import org.quarkword.security.Capabilities;

/**
 * The network services' check with the security module: the current identity must hold the capability for the binding
 * in effect. In a network only network administrators and the system identity hold a network capability (a grant
 * stored on a role or user is ignored). On a single site the system identity holds it, and so does a user given it
 * directly or through a role (WordPress's roles hold none, but a {@code CapabilityBuildItem} or a stored role table
 * can); the network-only names are stripped only in a network. Replaces core's check that allows everyone. With
 * Quarkus REST, the extension maps the refusal to 401 or 403 ({@link CapabilityDeniedMapper}).
 */
@ApplicationScoped
public class SecurityCapabilityCheck implements CapabilityCheck {

    @Inject
    Capabilities capabilities;

    /**
     * Refuses with {@link CapabilityDeniedException}, which says whether the caller is anonymous (no identity, as
     * outside a request, counts as anonymous, as {@code CapabilityGuard} counts it).
     */
    @Override
    public void requireNetwork(String capability) {
        SecurityIdentity identity = JpaCapabilities.currentIdentity();
        if (!capabilities.can(identity, capability, Optional.empty())) {
            throw new CapabilityDeniedException(capability, identity == null || identity.isAnonymous());
        }
    }
}

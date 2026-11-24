package org.quarkword.security.runtime;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.security.ProfileClaims;
import org.quarkword.security.QuarkWordPrincipal;
import org.quarkword.security.SystemIdentity;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfileProvisioner;

/**
 * Adds QuarkWord to every identity authenticated with a token of the default OIDC tenant that carries {@code sub}: the
 * profile of that subject (created on first sight), a {@link QuarkWordPrincipal} attribute, the user's role keys on
 * the request's site as its only roles, and a permission checker for capabilities. A {@code sub} that is no usable
 * subject fails authentication.
 *
 * <p>A subject is unique only for its issuer, and profiles are keyed by the subject alone, so only the default
 * tenant's tokens are users: a token verified by another tenant (Quarkus OIDC names the tenant in the identity's
 * {@code tenant-id} attribute), or by another token mechanism (no such attribute), is logged out for QuarkWord, as is
 * a token without {@code sub}, an identity whose principal is no token (an opaque token checked by introspection or
 * UserInfo) and the identity of any other mechanism. Such an identity keeps its principal, attributes, roles and
 * permissions, except that its permissions answer no capability: capabilities are QuarkWord's alone, and are never
 * granted by the permissions a mechanism gave an identity (Quarkus OIDC turns each token scope into one). Anonymous
 * identities, the system identity and identities already augmented pass unchanged. The request's site is taken before
 * the blocking work; the roles never include a network role.
 */
@ApplicationScoped
public class QuarkWordIdentityAugmentor implements SecurityIdentityAugmentor {

    /** The identity attribute in which Quarkus OIDC names the tenant that verified the token. */
    static final String TENANT_ATTRIBUTE = "tenant-id";

    /** The default tenant's id: Quarkus OIDC's {@code Default} unless the application names it otherwise. */
    @ConfigProperty(name = "quarkus.oidc.tenant-id", defaultValue = "Default")
    String defaultTenant;

    @Inject
    UserProfileProvisioner provisioner;

    @Inject
    IdentityPermissions permissions;

    @Inject
    CheckSite checkSite;

    @Inject
    CapabilityReads reads;

    @Inject
    SiteRoleTables tables;

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()
                || SystemIdentity.isSystem(identity)
                || identity.getAttribute(QuarkWordPrincipal.ATTRIBUTE) != null) {
            return Uni.createFrom().item(identity);
        }
        if (!(identity.getPrincipal() instanceof JsonWebToken)) {
            // an opaque token checked by introspection, UserInfo, or another mechanism: no QuarkWord user
            return Uni.createFrom().item(loggedOut(identity));
        }
        if (!defaultTenant.equals(identity.getAttribute(TENANT_ATTRIBUTE))) {
            return Uni.createFrom().item(loggedOut(identity));
        }
        Optional<ProfileClaims> claims;
        try {
            claims = IdentityClaims.of(identity);
        } catch (AuthenticationFailedException unusable) {
            return Uni.createFrom().failure(unusable);
        }
        if (claims.isEmpty()) {
            return Uni.createFrom().item(loggedOut(identity));
        }
        Optional<SiteId> site = checkSite.current();
        return context.runBlocking(() -> augmented(identity, claims.get(), site));
    }

    /**
     * {@code identity} as it is (principal, attributes, credentials, roles), with its own permission checks answering
     * no capability: an identity that is no QuarkWord user.
     */
    private static SecurityIdentity loggedOut(SecurityIdentity identity) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addAttributes(identity.getAttributes())
                .addCredentials(identity.getCredentials())
                .addRoles(identity.getRoles())
                .addPermissionChecker(IdentityPermissions.withoutCapabilities(identity))
                .build();
    }

    /**
     * What Quarkus's {@code QuarkusSecurityIdentity.builder(identity)} copies (principal, attributes, credentials, and
     * the identity's own permission checks, which keep answering every permission that is not a capability), except
     * its roles: the mechanism's roles (token groups, realm or client roles) are replaced by the user's role keys on
     * the request's site.
     */
    private SecurityIdentity augmented(SecurityIdentity identity, ProfileClaims claims, Optional<SiteId> site) {
        UserProfile profile = provisioner.provision(claims);
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addAttributes(identity.getAttributes())
                .addCredentials(identity.getCredentials())
                .addPermissionChecker(IdentityPermissions.withoutCapabilities(identity))
                .addAttribute(QuarkWordPrincipal.ATTRIBUTE, new QuarkWordPrincipal(profile.id(), profile.subject()))
                .addPermissionChecker(permissions.checker(new Actor.User(profile.id())));
        site.ifPresent(id -> builder.addRoles(siteRoles(id, profile.id())));
        return builder.build();
    }

    private Set<String> siteRoles(SiteId site, UserId user) {
        return reads.membership(site, user)
                .map(membership -> (Set<String>) new LinkedHashSet<>(
                        AllCaps.roles(tables.byKey(site), membership.roles(), membership.capabilities())))
                .orElseGet(Set::of);
    }
}

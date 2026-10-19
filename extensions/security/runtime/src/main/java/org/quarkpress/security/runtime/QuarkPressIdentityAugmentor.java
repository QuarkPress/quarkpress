package org.quarkpress.security.runtime;

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
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfileProvisioner;

/**
 * Adds QuarkPress to every identity authenticated with a token that carries {@code sub}: the profile of that subject
 * (created on first sight), a {@link QuarkPressPrincipal} attribute, the user's role keys on the request's site as its
 * only roles, and a permission checker for capabilities. A {@code sub} that is no usable subject fails authentication.
 * Anonymous identities, the system identity, identities already augmented, tokens without {@code sub} and identities
 * of other mechanisms pass unchanged, so QuarkPress treats them as logged out. The request's site is taken before the
 * blocking work; the roles never include a network role.
 */
@ApplicationScoped
public class QuarkPressIdentityAugmentor implements SecurityIdentityAugmentor {

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
                || identity.getAttribute(Actor.SYSTEM_ATTRIBUTE) != null
                || identity.getAttribute(QuarkPressPrincipal.ATTRIBUTE) != null) {
            return Uni.createFrom().item(identity);
        }
        Optional<ProfileClaims> claims;
        try {
            claims = IdentityClaims.of(identity);
        } catch (AuthenticationFailedException unusable) {
            return Uni.createFrom().failure(unusable);
        }
        if (claims.isEmpty()) {
            return Uni.createFrom().item(identity);
        }
        Optional<SiteId> site = checkSite.current();
        return context.runBlocking(() -> augmented(identity, claims.get(), site));
    }

    /**
     * What Quarkus's {@code QuarkusSecurityIdentity.builder(identity)} copies (principal, attributes, credentials, and
     * the identity's own permission checks, which keep answering as before), except its roles: the mechanism's roles
     * (token groups, realm or client roles) are replaced by the user's role keys on the request's site.
     */
    private SecurityIdentity augmented(SecurityIdentity identity, ProfileClaims claims, Optional<SiteId> site) {
        UserProfile profile = provisioner.provision(claims);
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(identity.getPrincipal())
                .addAttributes(identity.getAttributes())
                .addCredentials(identity.getCredentials())
                .addPermissionChecker(identity::checkPermission)
                .addAttribute(QuarkPressPrincipal.ATTRIBUTE, new QuarkPressPrincipal(profile.id(), profile.subject()))
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

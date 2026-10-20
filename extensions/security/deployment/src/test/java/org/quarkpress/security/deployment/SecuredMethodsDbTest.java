package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.PermissionChecker;
import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.Blocking;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.QuarkPressPrincipal;

/** Primitive capabilities with @PermissionsAllowed, and a meta capability with a @PermissionChecker. */
class SecuredMethodsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(
            SecuredMethodsDbTest.class,
            TestData.class,
            Identities.class,
            TestJwt.class,
            Drafts.class,
            PostChecks.class);

    @ApplicationScoped
    public static class Drafts {

        @PermissionsAllowed("edit_posts")
        public String write() {
            return "written";
        }

        @PermissionsAllowed("edit_post")
        public String edit(PostFacts post) {
            return "edited " + post.id().value();
        }

        @RolesAllowed("administrator")
        public String administer() {
            return "administered";
        }

        @RolesAllowed("subscriber")
        public String subscribe() {
            return "subscribed";
        }
    }

    @ApplicationScoped
    public static class PostChecks {

        @Inject
        Capabilities capabilities;

        @PermissionChecker("edit_post")
        @Blocking
        public boolean canEdit(SecurityIdentity identity, PostFacts post) {
            return capabilities.can(identity, "edit_post", Optional.of(post));
        }
    }

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    Drafts drafts;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity member(String roles) {
        SecurityIdentity identity = Identities.augment(Identities.jwt("sub-" + UUID.randomUUID(), null, null, null));
        TestData.member(
                dataSource, MAIN, QuarkPressPrincipal.of(identity).orElseThrow().user(), roles, "{}");
        return identity;
    }

    private static UserId user(SecurityIdentity identity) {
        return QuarkPressPrincipal.of(identity).orElseThrow().user();
    }

    @Test
    void aPrimitiveCapabilityGatesAMethod() {
        SecurityIdentity contributor = member("[\"contributor\"]");
        SecurityIdentity subscriber = member("[\"subscriber\"]");
        assertThat(Identities.as(contributor, () -> drafts.write())).isEqualTo("written");
        assertThatThrownBy(() -> Identities.as(subscriber, () -> drafts.write()))
                .isInstanceOf(ForbiddenException.class);
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
        assertThatThrownBy(() -> Identities.as(anonymous, () -> drafts.write()))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void aTokenScopeNamedLikeTheCapabilityDoesNotOpenTheMethod() {
        String subject = "sub-" + UUID.randomUUID();
        SecurityIdentity first = Identities.augment(Identities.jwt(subject, null, null, null));
        TestData.member(dataSource, MAIN, user(first), "[\"subscriber\"]", "{}");
        SecurityIdentity scoped = Identities.augment(Identities.scoped(subject, Set.of("edit_posts")));
        assertThatThrownBy(() -> Identities.as(scoped, () -> drafts.write())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anOpaqueTokensScopeNamedLikeTheCapabilityDoesNotOpenTheMethod() {
        SecurityIdentity opaque = Identities.augment(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("opaque-" + UUID.randomUUID()))
                .addAttribute(Identities.TENANT_ATTRIBUTE, Identities.DEFAULT_TENANT)
                .addPermissionAsString("edit_posts")
                .build());
        assertThatThrownBy(() -> Identities.as(opaque, () -> drafts.write())).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aMetaCapabilityIsCheckedAgainstTheMethodsArgument() {
        SecurityIdentity contributor = member("[\"contributor\"]");
        PostFacts ownDraft =
                PostFacts.of(PostId.of(31), PostTypeCapabilities.POST, "draft", Optional.of(user(contributor)));
        PostFacts ownPublished =
                PostFacts.of(PostId.of(32), PostTypeCapabilities.POST, "publish", Optional.of(user(contributor)));
        assertThat(Identities.as(contributor, () -> drafts.edit(ownDraft))).isEqualTo("edited 31");
        assertThatThrownBy(() -> Identities.as(contributor, () -> drafts.edit(ownPublished)))
                .isInstanceOf(ForbiddenException.class);
        SecurityIdentity editor = member("[\"editor\"]");
        assertThat(Identities.as(editor, () -> drafts.edit(ownPublished))).isEqualTo("edited 32");
    }

    @Test
    void anIdentityProvidersRoleIsNoSiteRole() {
        String subject = "sub-" + UUID.randomUUID();
        SecurityIdentity first = Identities.augment(Identities.jwt(subject, null, null, null));
        TestData.member(dataSource, MAIN, user(first), "[\"subscriber\"]", "{}");
        // the token's groups (and the roles the OIDC mechanism made of them) name a WordPress role
        SecurityIdentity subscriber = Identities.augment(
                Identities.token(Map.of("sub", subject, "groups", List.of("administrator")), Set.of("administrator")));
        assertThatThrownBy(() -> Identities.as(subscriber, () -> drafts.administer()))
                .isInstanceOf(ForbiddenException.class);
        assertThat(Identities.as(subscriber, () -> drafts.subscribe())).isEqualTo("subscribed");
    }
}

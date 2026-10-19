package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.security.CapCheck;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserCapabilitiesFilter;
import org.quarkpress.security.UserProfiles;

class IdentityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(
            IdentityDbTest.class, TestData.class, Identities.class, TestJwt.class, ThreadRecorder.class);

    /** Records whether checks read their data on an event-loop thread. */
    @ApplicationScoped
    public static class ThreadRecorder implements UserCapabilitiesFilter {
        static final List<Boolean> ON_EVENT_LOOP = new CopyOnWriteArrayList<>();

        @Override
        public Map<String, Boolean> apply(Map<String, Boolean> caps, CapCheck check) {
            ON_EVENT_LOOP.add(io.vertx.core.Context.isOnEventLoopThread());
            return caps;
        }
    }

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    UserProfiles profiles;

    @Inject
    Capabilities capabilities;

    @Inject
    Vertx vertx;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private static String sub() {
        return "sub-" + UUID.randomUUID();
    }

    @Test
    void aFirstAuthenticationProvisionsTheProfileAndAddsThePrincipal() {
        String subject = sub();
        String word = "carol" + UUID.randomUUID().toString().substring(0, 6).replace("-", "");
        SecurityIdentity identity = Identities.augment(Identities.jwt(subject, word, "Carol", "Chen"));
        QuarkPressPrincipal principal = QuarkPressPrincipal.of(identity).orElseThrow();
        assertThat(principal.subject()).isEqualTo(subject);
        assertThat(profiles.find(principal.user()).orElseThrow().nicename()).isEqualTo(word);
        assertThat(profiles.find(principal.user()).orElseThrow().displayName()).isEqualTo("Carol Chen");
        assertThat(identity.getPrincipal().getName()).isEqualTo(word);
    }

    /** Signs a subject in with a token once, so it has a profile, and makes it a contributor on the main site. */
    private String contributorSubject() {
        String subject = sub();
        SecurityIdentity first = Identities.augment(Identities.jwt(subject, null, null, null));
        TestData.member(
                dataSource, MAIN, QuarkPressPrincipal.of(first).orElseThrow().user(), "[\"contributor\"]", "{}");
        return subject;
    }

    private static void assertLoggedOut(SecurityIdentity identity) {
        assertThat(QuarkPressPrincipal.of(identity)).isEmpty();
        assertThat(identity.getRoles()).isEmpty();
        assertThat(identity.checkPermission(new StringPermission("exist"))
                        .await()
                        .indefinitely())
                .isFalse();
        assertThat(identity.checkPermission(new StringPermission("edit_posts"))
                        .await()
                        .indefinitely())
                .isFalse();
    }

    @Test
    void anIdentityWithoutATokenIsLeftAsItIs() {
        // a name equal to a signed-in subject (Basic, mTLS, an application's own mechanism) never becomes that user
        String subject = contributorSubject();
        long profilesBefore = Jdbc.count(dataSource, "select count(*) from qp_user_profile");
        SecurityIdentity named = Identities.named(subject);
        SecurityIdentity augmented = Identities.augment(named);
        assertLoggedOut(augmented);
        assertThat(Identities.augmentByQuarkPress(named)).isSameAs(named);
        String stranger = "plain" + UUID.randomUUID().toString().substring(0, 8);
        assertLoggedOut(Identities.augment(Identities.named(stranger)));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ?", stranger))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile"))
                .isEqualTo(profilesBefore);
    }

    @Test
    void aTokenWithoutSubIsLeftAsItIs() {
        String subject = contributorSubject();
        // a token whose preferred_username (its principal name) is another user's subject
        SecurityIdentity token = Identities.token(Map.of("preferred_username", subject), Set.of());
        assertThat(token.getPrincipal().getName()).isEqualTo(subject);
        assertLoggedOut(Identities.augment(token));
        assertThat(Identities.augmentByQuarkPress(token)).isSameAs(token);
    }

    @Test
    void anUnusableSubjectFailsAuthentication() {
        for (String subject : List.of("abc ", " abc", "   ", "", "x".repeat(256))) {
            assertThatThrownBy(() -> Identities.augmentByQuarkPress(Identities.jwt(subject, null, null, null)))
                    .as("sub %s", subject)
                    .isInstanceOf(AuthenticationFailedException.class);
        }
        assertThatThrownBy(() -> Identities.augmentByQuarkPress(Identities.token(Map.of("sub", 42), Set.of())))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void theTokensOwnRolesAreNotCarriedOver() {
        String subject = sub();
        SecurityIdentity first = Identities.augment(Identities.jwt(subject, null, null, null));
        TestData.member(
                dataSource, MAIN, QuarkPressPrincipal.of(first).orElseThrow().user(), "[\"subscriber\"]", "{}");
        SecurityIdentity augmented = Identities.augment(Identities.token(
                Map.of("sub", subject, "groups", List.of("administrator", "operators")),
                Set.of("administrator", "operators")));
        assertThat(augmented.getRoles()).containsExactly("subscriber");
        assertThat(augmented.hasRole("administrator")).isFalse();
    }

    @Test
    void theAuthenticationsOwnPermissionCheckersStillAnswer() {
        SecurityIdentity source = QuarkusSecurityIdentity.builder()
                .setPrincipal(new TestJwt(Map.of("sub", sub())))
                .addPermissionChecker(
                        permission -> Uni.createFrom().item(permission.equals(new StringPermission("export", "run"))))
                .addPermission(new StringPermission("report", "read"))
                .build();
        SecurityIdentity augmented = Identities.augment(source);
        assertThat(QuarkPressPrincipal.of(augmented)).isPresent();
        assertThat(augmented
                        .checkPermission(new StringPermission("export", "run"))
                        .await()
                        .indefinitely())
                .isTrue();
        assertThat(augmented
                        .checkPermission(new StringPermission("report", "read"))
                        .await()
                        .indefinitely())
                .isTrue();
        assertThat(augmented
                        .checkPermission(new StringPermission("export", "delete"))
                        .await()
                        .indefinitely())
                .isFalse();
    }

    @Test
    void rolesAreTheRegisteredRoleKeysOnTheRequestSite() {
        String subject = sub();
        SecurityIdentity first = Identities.augment(Identities.jwt(subject, null, null, null));
        QuarkPressPrincipal principal = QuarkPressPrincipal.of(first).orElseThrow();
        assertThat(first.getRoles()).isEmpty();
        TestData.member(dataSource, MAIN, principal.user(), "[\"editor\",\"shop_manager\"]", "{\"author\":false}");
        TestData.networkAdmin(dataSource, principal.user());
        SecurityIdentity again = Identities.augment(Identities.jwt(subject, null, null, null));
        assertThat(again.getRoles())
                .containsExactlyInAnyOrder("editor", "author")
                .doesNotContain("super_admin");
    }

    @Test
    void theIdentityAnswersCapabilitiesAsStringPermissions() {
        SecurityIdentity identity = Identities.augment(Identities.jwt(sub(), null, null, null));
        QuarkPressPrincipal principal = QuarkPressPrincipal.of(identity).orElseThrow();
        TestData.member(dataSource, MAIN, principal.user(), "[\"contributor\"]", "{}");
        assertThat(identity.checkPermission(new StringPermission("edit_posts"))
                        .await()
                        .indefinitely())
                .isTrue();
        assertThat(identity.checkPermission(new StringPermission("publish_posts"))
                        .await()
                        .indefinitely())
                .isFalse();
        assertThat(identity.checkPermission(new StringPermission("edit_posts", "write"))
                        .await()
                        .indefinitely())
                .isFalse();
        assertThat(identity.checkPermission(new StringPermission("exist"))
                        .await()
                        .indefinitely())
                .isTrue();
    }

    @Test
    void aSystemAttributeOnATokenIdentityGainsNothing() {
        for (Object value : List.of(Boolean.TRUE, Boolean.FALSE, "true")) {
            SecurityIdentity forged = QuarkusSecurityIdentity.builder(Identities.jwt(sub(), null, null, null))
                    .addAttribute("org.quarkpress.security.system", value)
                    .build();
            SecurityIdentity augmented = Identities.augment(forged);
            assertThat(QuarkPressPrincipal.of(augmented)).isPresent();
            assertThat(augmented.getRoles()).isEmpty();
            assertThat(capabilities.can(augmented, "manage_options", Optional.empty()))
                    .isFalse();
            assertThat(capabilities.can(augmented, "read", Optional.empty())).isFalse();
            assertThat(augmented
                            .checkPermission(new StringPermission("manage_options"))
                            .await()
                            .indefinitely())
                    .isFalse();
        }
    }

    @Test
    void anonymousAndAlreadyAugmentedIdentitiesPassThrough() {
        SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(""))
                .setAnonymous(true)
                .build();
        assertThat(Identities.augmentByQuarkPress(anonymous)).isSameAs(anonymous);
        SecurityIdentity augmented = Identities.augmentByQuarkPress(Identities.jwt(sub(), null, null, null));
        assertThat(Identities.augmentByQuarkPress(augmented)).isSameAs(augmented);
    }

    @Test
    void aCheckFromTheEventLoopRunsItsReadsOnAWorker() throws Exception {
        SecurityIdentity identity = Identities.augment(Identities.jwt(sub(), null, null, null));
        QuarkPressPrincipal principal = QuarkPressPrincipal.of(identity).orElseThrow();
        TestData.member(dataSource, MAIN, principal.user(), "[\"author\"]", "{}");
        ThreadRecorder.ON_EVENT_LOOP.clear();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        vertx.runOnContext(ignored -> identity.checkPermission(new StringPermission("publish_posts"))
                .subscribe()
                .with(answer::complete, answer::completeExceptionally));
        assertThat(answer.get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(ThreadRecorder.ON_EVENT_LOOP).isNotEmpty().containsOnly(false);
    }
}

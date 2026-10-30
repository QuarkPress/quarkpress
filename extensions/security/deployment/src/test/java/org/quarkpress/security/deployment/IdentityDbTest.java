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
import jakarta.json.Json;
import jakarta.json.JsonValue;
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

    private Optional<String> emailOf(SecurityIdentity identity) {
        return profiles.find(QuarkPressPrincipal.of(identity).orElseThrow().user())
                .orElseThrow()
                .email();
    }

    @Test
    void anEmailIsProvisionedUnlessTheTokenSaysItIsUnverified() {
        // no email_verified claim counts as verified
        String subject = sub();
        assertThat(emailOf(Identities.augment(
                        Identities.token(Map.of("sub", subject, "email", "first@quarkpress.test"), Set.of()))))
                .contains("first@quarkpress.test");
        // email_verified other than absent, true or the text true (any case) is unverified: the stored e-mail stays
        List<Object> unverifiedValues = List.of(
                Boolean.FALSE,
                JsonValue.FALSE,
                "false",
                Json.createValue("FALSE"),
                "yes",
                "1",
                1,
                Json.createValue(1),
                JsonValue.NULL,
                "",
                Map.of("verified", true));
        for (Object unverified : unverifiedValues) {
            assertThat(emailOf(Identities.augment(Identities.token(
                            Map.of("sub", subject, "email", "other@quarkpress.test", "email_verified", unverified),
                            Set.of()))))
                    .as("email_verified %s", unverified)
                    .contains("first@quarkpress.test");
            assertThat(emailOf(Identities.augment(Identities.token(
                            Map.of("sub", sub(), "email", "other@quarkpress.test", "email_verified", unverified),
                            Set.of()))))
                    .as("email_verified %s", unverified)
                    .isEmpty();
        }
        // email_verified true as a Boolean, and the text true in any case
        for (Object verified : List.of(Boolean.TRUE, "true", "TRUE", Json.createValue("True"))) {
            String fresh = sub();
            assertThat(emailOf(Identities.augment(Identities.token(
                            Map.of("sub", fresh, "email", "v@quarkpress.test", "email_verified", verified), Set.of()))))
                    .as("email_verified %s", verified)
                    .contains("v@quarkpress.test");
        }
        // email_verified true, as a JSON value, and the e-mail as a JSON string
        assertThat(emailOf(Identities.augment(Identities.token(
                        Map.of(
                                "sub",
                                subject,
                                "email",
                                Json.createValue("second@quarkpress.test"),
                                "email_verified",
                                JsonValue.TRUE),
                        Set.of()))))
                .contains("second@quarkpress.test");
        // an e-mail longer than a profile stores is no e-mail
        String longest = "m".repeat(100 - "@quarkpress.test".length()) + "@quarkpress.test";
        assertThat(emailOf(
                        Identities.augment(Identities.token(Map.of("sub", subject, "email", "x" + longest), Set.of()))))
                .contains("second@quarkpress.test");
        assertThat(emailOf(Identities.augment(Identities.token(Map.of("sub", subject, "email", longest), Set.of()))))
                .contains(longest);
        // a blank e-mail is no e-mail: the stored one stays, and a new profile gets none
        for (String blank : List.of("", " ", "\t")) {
            assertThat(emailOf(Identities.augment(Identities.token(Map.of("sub", subject, "email", blank), Set.of()))))
                    .as("email '%s'", blank)
                    .contains(longest);
            assertThat(emailOf(Identities.augment(Identities.token(Map.of("sub", sub(), "email", blank), Set.of()))))
                    .as("email '%s'", blank)
                    .isEmpty();
        }
        // an e-mail that is not text is no e-mail
        assertThat(emailOf(Identities.augment(Identities.token(Map.of("sub", sub(), "email", 42), Set.of()))))
                .isEmpty();
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
    void anIdentityWithoutATokenIsLoggedOut() {
        // a name equal to a signed-in subject (Basic, mTLS, an application's own mechanism) never becomes that user
        String subject = contributorSubject();
        long profilesBefore = Jdbc.count(dataSource, "select count(*) from qp_user_profile");
        SecurityIdentity named = Identities.named(subject);
        SecurityIdentity augmented = Identities.augment(named);
        assertLoggedOut(augmented);
        SecurityIdentity passed = Identities.augmentByQuarkPress(named);
        assertThat(passed.getPrincipal()).isSameAs(named.getPrincipal());
        assertThat(passed.getAttributes()).isEqualTo(named.getAttributes());
        String stranger = "plain" + UUID.randomUUID().toString().substring(0, 8);
        assertLoggedOut(Identities.augment(Identities.named(stranger)));
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ?", stranger))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile"))
                .isEqualTo(profilesBefore);
    }

    @Test
    void anIdentityWithoutATokenAnswersNoCapabilityFromItsPermissions() {
        // an opaque token checked by introspection: a plain principal, the tenant attribute, scopes as permissions;
        // and the same without the attribute, as another mechanism leaves it
        for (String tenant : java.util.Arrays.asList(Identities.DEFAULT_TENANT, null)) {
            QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal("opaque-" + UUID.randomUUID()))
                    .addRole("operators")
                    .addPermissionsAsString(Set.of("manage_options", "manage_network_options", "export:run"));
            if (tenant != null) {
                builder.addAttribute(Identities.TENANT_ATTRIBUTE, tenant);
            }
            SecurityIdentity augmented = Identities.augment(builder.build());
            assertThat(QuarkPressPrincipal.of(augmented))
                    .as("tenant %s", tenant)
                    .isEmpty();
            assertThat(answers(augmented, new StringPermission("manage_options")))
                    .as("tenant %s", tenant)
                    .isFalse();
            assertThat(answers(augmented, new StringPermission("manage_network_options")))
                    .as("tenant %s", tenant)
                    .isFalse();
            assertThat(answers(augmented, new StringPermission("export", "run")))
                    .as("tenant %s", tenant)
                    .isTrue();
            assertThat(augmented.getRoles()).as("tenant %s", tenant).containsExactly("operators");
        }
    }

    @Test
    void aTokenWithoutSubIsLoggedOut() {
        String subject = contributorSubject();
        // a token whose preferred_username (its principal name) is another user's subject
        SecurityIdentity token = Identities.token(Map.of("preferred_username", subject), Set.of());
        assertThat(token.getPrincipal().getName()).isEqualTo(subject);
        assertLoggedOut(Identities.augment(token));
        SecurityIdentity passed = Identities.augmentByQuarkPress(token);
        assertThat(passed.getPrincipal()).isSameAs(token.getPrincipal());
        assertThat(passed.getAttributes()).isEqualTo(token.getAttributes());
        // its scopes answer no capability
        SecurityIdentity scoped = Identities.token(
                Identities.DEFAULT_TENANT,
                Map.of("preferred_username", subject),
                Set.of(),
                Set.of("edit_posts", "export:run"));
        SecurityIdentity augmented = Identities.augment(scoped);
        assertLoggedOut(augmented);
        assertThat(answers(augmented, new StringPermission("export", "run"))).isTrue();
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
                .addAttribute(Identities.TENANT_ATTRIBUTE, Identities.DEFAULT_TENANT)
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

    private boolean answers(SecurityIdentity identity, StringPermission permission) {
        return identity.checkPermission(permission).await().indefinitely();
    }

    @Test
    void tokenScopesNamedLikeCapabilitiesGrantNothing() {
        // Quarkus OIDC turns each scope into a permission; only QuarkPress decides a capability
        String subscriber = sub();
        SecurityIdentity first = Identities.augment(Identities.jwt(subscriber, null, null, null));
        TestData.member(
                dataSource, MAIN, QuarkPressPrincipal.of(first).orElseThrow().user(), "[\"subscriber\"]", "{}");
        Set<String> scopes = Set.of("manage_options", "upload_files", "edit_posts", "export:run");
        SecurityIdentity scoped = Identities.augment(Identities.scoped(subscriber, scopes));
        assertThat(QuarkPressPrincipal.of(scoped)).isPresent();
        assertThat(answers(scoped, new StringPermission("manage_options"))).isFalse();
        assertThat(answers(scoped, new StringPermission("upload_files"))).isFalse();
        assertThat(answers(scoped, new StringPermission("edit_posts"))).isFalse();
        assertThat(answers(scoped, new StringPermission("read"))).isTrue();
        // a scope that is not a capability (it has an action) still answers as the mechanism made it
        assertThat(answers(scoped, new StringPermission("export", "run"))).isTrue();
        // a user who is no member: a read scope gives no read
        SecurityIdentity stranger = Identities.augment(Identities.scoped(sub(), Set.of("read", "exist")));
        assertThat(QuarkPressPrincipal.of(stranger)).isPresent();
        assertThat(answers(stranger, new StringPermission("read"))).isFalse();
    }

    @Test
    void aTokenOfAnotherTenantIsLoggedOutAndCreatesNoProfile() {
        // the same sub from another issuer is another user: it never becomes the profile of this subject
        String subject = contributorSubject();
        long profilesBefore = Jdbc.count(dataSource, "select count(*) from qp_user_profile");
        for (String tenant : java.util.Arrays.asList("customers", "default", null)) {
            SecurityIdentity foreign = Identities.token(
                    tenant, Map.of("sub", subject, "scope", "edit_posts"), Set.of("operators"), Set.of("edit_posts"));
            SecurityIdentity augmented = Identities.augment(foreign);
            // not a QuarkPress user: no principal, no capability (its scope included); its own roles stay its own
            assertThat(QuarkPressPrincipal.of(augmented))
                    .as("tenant %s", tenant)
                    .isEmpty();
            assertThat(augmented.getRoles()).as("tenant %s", tenant).containsExactly("operators");
            assertThat(answers(augmented, new StringPermission("edit_posts")))
                    .as("tenant %s", tenant)
                    .isFalse();
            assertThat(answers(augmented, new StringPermission("exist")))
                    .as("tenant %s", tenant)
                    .isFalse();
            assertThat(capabilities.can(augmented, "edit_posts", Optional.empty()))
                    .as("tenant %s", tenant)
                    .isFalse();
            // a fresh subject of another tenant gets no profile either
            String stranger = sub();
            assertLoggedOut(Identities.augment(Identities.token(tenant, Map.of("sub", stranger), Set.of(), Set.of())));
            assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ?", stranger))
                    .isZero();
        }
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile"))
                .isEqualTo(profilesBefore);
        // the default tenant's token of the same subject is that user
        assertThat(QuarkPressPrincipal.of(Identities.augment(Identities.jwt(subject, null, null, null))))
                .isPresent();
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

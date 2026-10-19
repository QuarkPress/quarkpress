package org.quarkpress.security.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.arc.Arc;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MissingObject;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserTarget;

class SystemIdentityDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            SecurityApp.with(SystemIdentityDbTest.class, TestData.class, Identities.class, SystemResource.class);

    @Path("/it/system")
    public static class SystemResource {

        @Inject
        SystemIdentity system;

        @GET
        public String become() {
            try {
                system.run(() -> {});
                return "ran";
            } catch (IllegalStateException refused) {
                return refused.getMessage();
            }
        }
    }

    @Inject
    SystemIdentity system;

    @Inject
    Capabilities capabilities;

    @Inject
    Vertx vertx;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private boolean asSystem(String capability) throws Exception {
        return system.call(() -> capabilities.can(capability));
    }

    @Test
    void theSystemHoldsEverythingButUnfilteredHtml() throws Exception {
        assertThat(asSystem("manage_options")).isTrue();
        assertThat(asSystem("create_sites")).isTrue();
        assertThat(asSystem("frobnicate")).isTrue();
        assertThat(asSystem("unfiltered_html")).isFalse();
        assertThat(asSystem("edit_css")).isFalse();
        assertThat(asSystem("do_not_allow")).isFalse();
        assertThat(asSystem("delete_site")).isFalse();
        assertThat(system.call(() -> capabilities.can("edit_post", new MissingObject(5))))
                .isFalse();
        assertThat(system.call(() -> capabilities.can("edit_user", new UserTarget(UserId.of(1)))))
                .isFalse();
        assertThat(system.identity()
                        .checkPermission(new StringPermission("publish_posts"))
                        .await()
                        .indefinitely())
                .isTrue();
        assertThat(SystemIdentity.isSystem(system.identity())).isTrue();
    }

    @Test
    void aUserCannotBecomeTheSystem() {
        UserId user = TestData.profile(dataSource, "user");
        assertThatThrownBy(() -> Identities.as(Identities.user(user, TestData.subject(dataSource, user)), () -> {
                    system.run(() -> {});
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("the system identity runs only where nobody is authenticated; the identity is");
    }

    @Test
    void anAuthenticatedIdentityWithoutAQuarkPressPrincipalCannotBecomeTheSystem() {
        SecurityIdentity basic = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal("mtls-client"))
                .build();
        assertThatThrownBy(() -> Identities.as(basic, () -> {
                    system.run(() -> {});
                    return null;
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the system identity runs only where nobody is authenticated; the identity is"
                        + " mtls-client");
    }

    @Test
    void theIdentityIsRestoredWhenTheWorkThrows() {
        Requests.run(() -> {
            CurrentIdentityAssociation association =
                    Arc.container().instance(CurrentIdentityAssociation.class).get();
            assertThatThrownBy(() -> system.run(() -> {
                        throw new IllegalArgumentException("work failed");
                    }))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("work failed");
            assertThat(association.getIdentity().isAnonymous()).isTrue();
            assertThatThrownBy(() -> system.call(() -> {
                        throw new java.io.IOException("io failed");
                    }))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("io failed");
            assertThat(association.getIdentity().isAnonymous()).isTrue();
        });
    }

    @Test
    void anEventLoopThreadCannotBecomeTheSystem() throws Exception {
        CompletableFuture<String> answer = new CompletableFuture<>();
        vertx.runOnContext(ignored -> {
            try {
                answer.complete(system.call(() -> "ran"));
            } catch (Exception refused) {
                answer.complete(refused.getClass().getSimpleName() + ": " + refused.getMessage());
            }
        });
        assertThat(answer.get(30, TimeUnit.SECONDS))
                .isEqualTo("IllegalStateException: the system identity cannot run on an event-loop thread");
    }

    @Test
    void theIdentityIsRestoredAndNestingWorks() {
        Requests.run(() -> {
            CurrentIdentityAssociation association =
                    Arc.container().instance(CurrentIdentityAssociation.class).get();
            assertThat(association.getIdentity().isAnonymous()).isTrue();
            system.run(() -> {
                assertThat(SystemIdentity.isSystem(association.getIdentity())).isTrue();
                system.run(() -> assertThat(SystemIdentity.isSystem(association.getIdentity()))
                        .isTrue());
                assertThat(SystemIdentity.isSystem(association.getIdentity())).isTrue();
            });
            assertThat(association.getIdentity().isAnonymous()).isTrue();
        });
    }

    @Test
    void withoutARequestContextOneIsActivatedForTheWork() throws Exception {
        assertThat(Arc.container().requestContext().isActive()).isFalse();
        assertThat(system.call(() -> Arc.container().requestContext().isActive()))
                .isTrue();
        assertThat(Arc.container().requestContext().isActive()).isFalse();
        assertThat(capabilities.anonymousCan(org.quarkpress.core.SiteId.of(1), "read", Optional.empty()))
                .isFalse();
    }

    @Test
    void anIdentityCarryingTheSystemAttributeNameIsNotTheSystem() {
        for (Object value : List.of(Boolean.TRUE, "true")) {
            SecurityIdentity forged = QuarkusSecurityIdentity.builder()
                    .setPrincipal(new QuarkusPrincipal(SystemIdentity.NAME))
                    .addAttribute("org.quarkpress.security.system", value)
                    .build();
            assertThat(SystemIdentity.isSystem(forged)).isFalse();
            assertThat(Identities.as(forged, () -> capabilities.can("manage_options")))
                    .isFalse();
            assertThat(Identities.as(forged, () -> capabilities.can("exist"))).isTrue();
        }
    }

    @Test
    void anHttpRequestCannotBecomeTheSystem() {
        given().get("/it/system")
                .then()
                .statusCode(200)
                .body(is("the system identity cannot run inside an HTTP request"));
    }
}

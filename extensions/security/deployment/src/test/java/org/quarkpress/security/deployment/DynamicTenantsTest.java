package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.oidc.OidcRequestContext;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.TenantConfigResolver;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * A {@link TenantConfigResolver} fails the build: a dynamic tenant built without an id carries the default tenant's id,
 * so its tokens could not be told from the default tenant's, and a subject of its issuer would sign in as the user of
 * the same subject.
 */
class DynamicTenantsTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.buildOnly(DynamicTenantsTest.class, Tenants.class)
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: the TenantConfigResolver "
                            + Tenants.class.getName()
                            + " adds dynamic OIDC tenants"));

    @ApplicationScoped
    public static class Tenants implements TenantConfigResolver {

        @Override
        public Uni<OidcTenantConfig> resolve(
                RoutingContext context, OidcRequestContext<OidcTenantConfig> requestContext) {
            return Uni.createFrom().nullItem();
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}

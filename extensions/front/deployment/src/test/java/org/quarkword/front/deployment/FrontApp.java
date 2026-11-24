package org.quarkword.front.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import java.util.UUID;

/**
 * Test applications with the front module (and so the posts, taxonomy, security and persistence modules) on
 * QuarkWord's datasource; Dev Services starts the database. No Keycloak: requests are anonymous. The multisite module
 * is on the test class path and turns itself on, so {@link #with} turns it off for a single site and
 * {@link #multisite} keeps it on. The HTTP layer trusts {@code X-Forwarded-Proto}, so a test can make an https request.
 */
public final class FrontApp {

    private FrontApp() {}

    /** A single-site install: every request belongs to the main site. */
    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(classes).overrideConfigKey("quarkword.multisite.enabled", "false");
    }

    /** A multisite install: the multisite module (an optional dependency of this one) turned on. */
    public static QuarkusExtensionTest multisite(Class<?>... classes) {
        return app(classes).overrideConfigKey("quarkword.multisite.enabled", "true");
    }

    /** For tests whose build or start must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return with(classes).overrideConfigKey("quarkus.datasource.\"quarkword\".devservices.enabled", "false");
    }

    private static QuarkusExtensionTest app(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(
                                TestDb.class, FrontApp.class, Http.class, Jdbc.class, Failures.class, TestData.class))
                .overrideConfigKey("quarkus.datasource.\"quarkword\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0")
                .overrideConfigKey("quarkus.http.proxy.proxy-address-forwarding", "true")
                // the optional media module is on the test class path: each application keeps its uploads apart
                .overrideConfigKey("quarkword.media.fs.directory", "target/media-" + UUID.randomUUID());
    }
}

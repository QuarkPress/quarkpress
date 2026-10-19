package org.quarkpress.security.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the security module on QuarkPress's datasource; Dev Services starts the database. No
 * Keycloak: identities are made by the tests.
 */
public final class SecurityApp {

    private SecurityApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(false, classes);
    }

    /** The security module in multisite mode (through {@link TestMultisiteMode}), without the multisite module. */
    public static QuarkusExtensionTest multisite(Class<?>... classes) {
        return app(true, classes);
    }

    /** For tests whose build must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return with(classes).overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
    }

    private static QuarkusExtensionTest app(boolean multisite, Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> {
                    jar.addClasses(classes)
                            .addClasses(
                                    TestDb.class,
                                    SecurityApp.class,
                                    Requests.class,
                                    Races.class,
                                    Jdbc.class,
                                    Failures.class,
                                    Identities.class,
                                    TestJwt.class,
                                    TestData.class);
                    if (multisite) {
                        jar.addClasses(TestMultisiteMode.class);
                    }
                })
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }
}

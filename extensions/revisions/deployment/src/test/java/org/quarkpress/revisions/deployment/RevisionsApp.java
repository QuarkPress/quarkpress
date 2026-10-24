package org.quarkpress.revisions.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the revisions module (and so the posts and security modules) on QuarkPress's datasource;
 * Dev Services starts the database. No Keycloak: identities are made by the tests (see {@link Identities}). Every
 * application includes {@link TestClock}, so dates are those a test sets.
 */
public final class RevisionsApp {

    private RevisionsApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(false, classes);
    }

    /** As {@link #with}, with security in multisite mode ({@link TestMultisiteMode}): checks name their site. */
    public static QuarkusExtensionTest multisite(Class<?>... classes) {
        return app(true, classes);
    }

    private static QuarkusExtensionTest app(boolean multisite, Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> {
                    jar.addClasses(classes)
                            .addClasses(
                                    TestDb.class,
                                    RevisionsApp.class,
                                    Races.class,
                                    Jdbc.class,
                                    Failures.class,
                                    Identities.class,
                                    TestData.class,
                                    TestClock.class);
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

    /** For tests whose build must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return with(classes).overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
    }
}

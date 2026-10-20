package org.quarkpress.posts.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the posts module on QuarkPress's datasource; Dev Services starts the database. No
 * Keycloak: identities are made by the tests (see {@link Identities}).
 */
public final class PostsApp {

    private PostsApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(false, classes);
    }

    /** The posts module with security in multisite mode (through {@link TestMultisiteMode}). */
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
                                    PostsApp.class,
                                    Requests.class,
                                    Races.class,
                                    Jdbc.class,
                                    Failures.class,
                                    Identities.class,
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

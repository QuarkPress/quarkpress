package org.quarkpress.restwp.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the REST module (and so posts, taxonomy and security) on QuarkPress's datasource; Dev
 * Services starts the database. No Keycloak: HTTP identities come from {@link TestBearer}.
 */
public final class RestApp {

    private RestApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(false, classes);
    }

    /** A multisite install: the multisite module (an optional dependency of this one) turned on. */
    public static QuarkusExtensionTest multisite(Class<?>... classes) {
        return app(true, classes);
    }

    /** For tests whose build or start must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return with(classes).overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
    }

    private static QuarkusExtensionTest app(boolean multisite, Class<?>... classes) {
        QuarkusExtensionTest test = new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(
                                TestDb.class,
                                RestApp.class,
                                TestBearer.class,
                                TestBearer.Request.class,
                                Http.class,
                                Races.class,
                                Jdbc.class,
                                Failures.class,
                                TestData.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
        if (multisite) {
            test.overrideConfigKey("quarkpress.multisite.enabled", "true");
        }
        return test;
    }
}

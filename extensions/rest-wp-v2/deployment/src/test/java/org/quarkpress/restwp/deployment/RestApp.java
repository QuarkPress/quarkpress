package org.quarkpress.restwp.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the REST module (and so posts, taxonomy and security) on QuarkPress's datasource; Dev
 * Services starts the database. No Keycloak: HTTP identities come from {@link TestBearer}.
 */
public final class RestApp {

    private RestApp() {}

    /**
     * A network: the multisite module is on the test class path and its own configuration turns it on
     * ({@code quarkpress.multisite.enabled=true}), so this application applies WordPress's multisite rules (an
     * administrator who is not a super admin manages no users) and resolves sites by domain and path. Use
     * {@link #singleSite} for a single site.
     */
    public static QuarkusExtensionTest network(Class<?>... classes) {
        return app(false, classes);
    }

    /**
     * A single-site install: the multisite module, which is on the test class path and turns itself on, turned off,
     * so WordPress's single-site rules apply (an administrator manages users).
     */
    public static QuarkusExtensionTest singleSite(Class<?>... classes) {
        return app(false, classes).overrideConfigKey("quarkpress.multisite.enabled", "false");
    }

    /** A multisite install: the multisite module (an optional dependency of this one) turned on. */
    public static QuarkusExtensionTest multisite(Class<?>... classes) {
        return app(true, classes);
    }

    /** For tests whose build or start must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return network(classes).overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
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

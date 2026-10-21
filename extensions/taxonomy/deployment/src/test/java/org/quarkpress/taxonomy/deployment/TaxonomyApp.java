package org.quarkpress.taxonomy.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the taxonomy module (and so the posts and security modules) on QuarkPress's datasource;
 * Dev Services starts the database. No Keycloak: identities are made by the tests (see {@link Identities}).
 */
public final class TaxonomyApp {

    private TaxonomyApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(
                                TestDb.class,
                                TaxonomyApp.class,
                                Races.class,
                                Jdbc.class,
                                Failures.class,
                                Identities.class,
                                TestData.class))
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

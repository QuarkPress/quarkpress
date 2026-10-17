package org.quarkpress.persistence.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/** Test applications on QuarkPress's datasource; Dev Services starts the database. */
public final class DbApp {

    private DbApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(
                                TestDb.class,
                                DbApp.class,
                                Requests.class,
                                TestSites.class,
                                Jdbc.class,
                                Json.class,
                                Failures.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }

    /** For tests whose build must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return with(classes).overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
    }
}

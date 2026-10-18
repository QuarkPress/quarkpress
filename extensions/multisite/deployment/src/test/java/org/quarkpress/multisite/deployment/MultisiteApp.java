package org.quarkpress.multisite.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/** Test applications with the multisite module on QuarkPress's datasource; Dev Services starts the database. */
public final class MultisiteApp {

    private MultisiteApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(TestDb.class, MultisiteApp.class, Requests.class, Jdbc.class, Failures.class))
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

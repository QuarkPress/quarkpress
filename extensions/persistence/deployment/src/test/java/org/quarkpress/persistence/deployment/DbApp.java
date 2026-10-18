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
                                Races.class,
                                Jdbc.class,
                                Json.class,
                                Failures.class,
                                TestFeatures.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }

    /** For tests whose build must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return with(classes).overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
    }

    /**
     * For tests whose build must fail with entities in the application: Hibernate ORM wants a datasource URL while it
     * builds. The address is reached only if the expected build failure does not happen: the application then starts
     * and fails on the connection.
     */
    public static QuarkusExtensionTest buildOnlyMapped(Class<?>... classes) {
        boolean mysql = TestDb.kind().equals("mysql");
        String url = mysql ? "jdbc:mysql://localhost:1/none" : "jdbc:postgresql://localhost:1/none";
        return buildOnly(classes)
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".jdbc.url", url)
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-version", mysql ? "8.4" : "17");
    }
}

package org.quarkpress.scheduling.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the scheduling module (and so the posts, security and persistence modules) on QuarkPress's
 * datasource; Dev Services starts the database. No Keycloak: identities are made by the tests (see
 * {@link Identities}). Every application includes {@link TestClock}, so dates are those a test sets. The Quarkus
 * scheduler is off unless {@link #scheduled} is used: tests call the jobs themselves.
 */
public final class SchedulingApp {

    private SchedulingApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(classes).overrideConfigKey("quarkus.scheduler.enabled", "false");
    }

    /** As {@link #with}, with the scheduler on and the polling job's interval set to {@code interval}. */
    public static QuarkusExtensionTest scheduled(String interval, Class<?>... classes) {
        return app(classes).overrideConfigKey("quarkpress.scheduling.interval", interval);
    }

    private static QuarkusExtensionTest app(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(
                                TestDb.class,
                                SchedulingApp.class,
                                Races.class,
                                Jdbc.class,
                                Failures.class,
                                Identities.class,
                                TestData.class,
                                TestClock.class))
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

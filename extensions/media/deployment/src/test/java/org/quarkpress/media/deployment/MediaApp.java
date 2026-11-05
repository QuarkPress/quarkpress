package org.quarkpress.media.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the media module (and so the posts and security modules) on QuarkPress's datasource; Dev
 * Services starts the database. No Keycloak: identities are made by the tests (see {@link Identities}). Every
 * application includes {@link TestClock}, so dates are those a test sets; {@link #with} adds the in-memory
 * {@link MemoryStorageBean} as the storage backend.
 */
public final class MediaApp {

    private MediaApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return app(false, true, classes);
    }

    /** As {@link #with}, with security in multisite mode ({@link TestMultisiteMode}): checks name their site. */
    public static QuarkusExtensionTest multisite(Class<?>... classes) {
        return app(true, true, classes);
    }

    /** As {@link #with} without {@link MemoryStorageBean}: the storage beans are those in {@code classes}. */
    public static QuarkusExtensionTest withoutStorage(Class<?>... classes) {
        return app(false, false, classes);
    }

    /** For tests whose build must fail: no storage bean but those in {@code classes}, and no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return withoutStorage(classes)
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".devservices.enabled", "false");
    }

    private static QuarkusExtensionTest app(boolean multisite, boolean storage, Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> {
                    jar.addClasses(classes)
                            .addClasses(
                                    TestDb.class,
                                    MediaApp.class,
                                    MemoryStorage.class,
                                    Races.class,
                                    Jdbc.class,
                                    Failures.class,
                                    Identities.class,
                                    TestData.class,
                                    TestClock.class);
                    if (storage) {
                        jar.addClasses(MemoryStorageBean.class);
                    }
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

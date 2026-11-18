package org.quarkpress.media.image.vips.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the vips module (and so the media, posts and security modules) on QuarkPress's datasource;
 * Dev Services starts the database. No Keycloak and no OIDC tenant. {@link TestStorage} is the storage backend. libvips
 * is not required, so the applications also start on a host without it.
 */
public final class VipsApp {

    private VipsApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes).addClasses(VipsApp.class, TestStorage.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0")
                .overrideConfigKey("quarkus.libvips.required", "false");
    }

    /**
     * The database the test uses: {@code -DtestDb=postgresql} (default) or {@code mysql}, passed by Maven as the
     * environment variable {@code QUARKPRESS_TEST_DB}. A copy of media's test helper
     * {@code org.quarkpress.media.deployment.TestDb.kind()}, which this module cannot see: keep the two in step.
     */
    static String kind() {
        String kind = System.getenv("QUARKPRESS_TEST_DB");
        if (kind == null || kind.isBlank()) {
            kind = "postgresql";
        }
        if (!kind.equals("postgresql") && !kind.equals("mysql")) {
            throw new IllegalStateException("QUARKPRESS_TEST_DB must be postgresql or mysql, not " + kind);
        }
        return kind;
    }
}

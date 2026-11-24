package org.quarkword.media.image.vips.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the vips module (and so the media, posts and security modules) and the ImageIO module (a test
 * dependency) on QuarkWord's datasource; Dev Services starts the database. No Keycloak and no OIDC tenant.
 * {@link TestStorage}, in memory, is the storage backend, and {@link Uploads} uploads through the media API. libvips is
 * not required, so the applications also start on a host without it.
 */
public final class VipsApp {

    private VipsApp() {}

    public static QuarkusExtensionTest with(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(
                        jar -> jar.addClasses(classes).addClasses(VipsApp.class, TestStorage.class, Uploads.class))
                .overrideConfigKey("quarkus.datasource.\"quarkword\".db-kind", kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0")
                .overrideConfigKey("quarkus.libvips.required", "false");
    }

    /**
     * As {@link #with}, as if the ImageIO module were not installed: its processor bean is left out of the application
     * (the module's jar is on the test class path of every application here).
     */
    public static QuarkusExtensionTest withoutImageIo(Class<?>... classes) {
        return with(classes)
                .overrideConfigKey(
                        "quarkus.arc.exclude-types", "org.quarkword.media.image.imageio.runtime.ImageIoProcessor");
    }

    /** The messages of {@code failure}, its causes and its suppressed exceptions, one per line. */
    static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (int depth = 0; failure != null && depth < 20; failure = failure.getCause(), depth++) {
            text.append(failure.getClass().getName())
                    .append(": ")
                    .append(failure.getMessage())
                    .append('\n');
            for (Throwable suppressed : failure.getSuppressed()) {
                text.append(messages(suppressed));
            }
        }
        return text.toString();
    }

    /**
     * The database the test uses: {@code -DtestDb=postgresql} (default) or {@code mysql}, passed by Maven as the
     * environment variable {@code QUARKWORD_TEST_DB}. A copy of media's test helper
     * {@code org.quarkword.media.deployment.TestDb.kind()}, which this module cannot see: keep the two in step.
     */
    static String kind() {
        String kind = System.getenv("QUARKWORD_TEST_DB");
        if (kind == null || kind.isBlank()) {
            kind = "postgresql";
        }
        if (!kind.equals("postgresql") && !kind.equals("mysql")) {
            throw new IllegalStateException("QUARKWORD_TEST_DB must be postgresql or mysql, not " + kind);
        }
        return kind;
    }
}

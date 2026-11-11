package org.quarkpress.media.storage.s3.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Test applications with the media module and this S3 backend on QuarkPress's datasource (the media test settings);
 * Dev Services start the database and moto, which holds the bucket {@value #BUCKET}.
 */
public final class S3App {

    /** The bucket Dev Services create in moto. */
    public static final String BUCKET = "quarkpress-test";

    private S3App() {}

    /** An application in multisite mode with the S3 backend in use on {@value #BUCKET}, under a fresh key prefix. */
    public static QuarkusExtensionTest active(Class<?>... classes) {
        return app(classes)
                .overrideRuntimeConfigKey("quarkpress.media.s3.bucket", BUCKET)
                .overrideRuntimeConfigKey("quarkpress.media.s3.key-prefix", "t-" + UUID.randomUUID());
    }

    /** An application in multisite mode with the S3 backend chosen, without a configured bucket. */
    public static QuarkusExtensionTest app(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes)
                        .addClasses(S3App.class, TestDb.class, Failures.class, TestMultisiteMode.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0")
                // the filesystem backend is on this module's test class path too
                .overrideConfigKey("quarkpress.media.storage", "s3")
                .overrideConfigKey("quarkus.s3.devservices.provider", "moto")
                .overrideConfigKey("quarkus.aws.devservices.moto.image-name", "motoserver/moto:5.2.5")
                .overrideConfigKey("quarkus.s3.devservices.buckets", BUCKET)
                // containers can take more than the default minute to start on shared CI runners
                .overrideConfigKey("quarkus.devservices.timeout", "5M");
    }

    /** A directory below target/ for one test class. */
    public static Path directory(String name) {
        return Path.of("target", "s3-apps", name).toAbsolutePath();
    }
}

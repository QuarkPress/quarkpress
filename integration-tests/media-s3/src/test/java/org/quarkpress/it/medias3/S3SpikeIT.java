package org.quarkpress.it.medias3;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The same tests against the packaged application: the JVM jar locally, the native executable in CI. Dev Services
 * start moto for it too and hand the application its endpoint.
 */
@QuarkusIntegrationTest
class S3SpikeIT extends S3SpikeDbTest {}

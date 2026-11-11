package org.quarkpress.it.medias3;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The same tests against the packaged application: the JVM jar locally, the native executable in CI. The key prefix
 * resource is named again here, as test resources are looked up on the test class itself.
 */
@QuarkusIntegrationTest
@WithTestResource(S3PrefixResource.class)
class MediaS3IT extends MediaS3DbTest {}

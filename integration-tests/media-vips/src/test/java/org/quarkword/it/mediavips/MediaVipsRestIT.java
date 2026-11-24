package org.quarkword.it.mediavips;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The REST tests against the packaged application: the JVM jar locally, the native executable in CI, where they prove
 * the uploads through WordPress's REST API (HEIC included), the post-process and the readiness check of libvips. The
 * storage directory resource is named again here, as test resources are looked up on the test class itself.
 */
@QuarkusIntegrationTest
@WithTestResource(MediaFilesResource.class)
class MediaVipsRestIT extends MediaVipsRestDbTest {}

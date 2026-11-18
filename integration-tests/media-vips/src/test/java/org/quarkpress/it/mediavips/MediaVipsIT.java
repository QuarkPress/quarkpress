package org.quarkpress.it.mediavips;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The same tests against the packaged application: the JVM jar locally (started with native access for libvips), the
 * native executable in CI. The storage directory resource is named again here, as test resources are looked up on the
 * test class itself.
 */
@QuarkusIntegrationTest
@WithTestResource(MediaFilesResource.class)
class MediaVipsIT extends MediaVipsDbTest {}

package org.quarkpress.it.restwp;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The outbound tests against the packaged application: the JVM jar locally, the native executable in CI. The profile's
 * local server runs in this JVM; the application, another process, reaches it over the loopback.
 */
@QuarkusIntegrationTest
class OutboundIT extends OutboundDbTest {}

package org.quarkpress.it.restwp;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** The same tests against the packaged application: the JVM jar locally, the native executable in CI. */
@QuarkusIntegrationTest
class RestWpV2IT extends RestWpV2DbTest {}

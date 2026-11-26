package org.quarkword.it.front;

import io.quarkus.test.junit.QuarkusIntegrationTest;

/** The same tests against the packaged application: the JVM jar locally, the native executable in CI. */
@QuarkusIntegrationTest
class FrontIT extends FrontDbTest {}

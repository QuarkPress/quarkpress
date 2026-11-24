package org.quarkword.it.keycloak;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusIntegrationTest;

/**
 * The same tests against the packaged application: the JVM jar locally, the native executable in CI. It runs in
 * production mode, so the provisioner's start check sees the client-credentials admin client.
 */
@QuarkusIntegrationTest
@WithTestResource(ProvisionerClient.class)
class KeycloakE2EIT extends KeycloakE2EDbTest {}

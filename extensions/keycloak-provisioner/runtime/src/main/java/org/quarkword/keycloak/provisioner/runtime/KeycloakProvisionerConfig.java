package org.quarkword.keycloak.provisioner.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Settings of the Keycloak provisioner. The admin client's connection and credentials are Quarkus's
 * {@code quarkus.keycloak.admin-client.*}.
 */
@ConfigMapping(prefix = "quarkword.keycloak-provisioner")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface KeycloakProvisionerConfig {

    /**
     * The realm users are created in, the one QuarkWord signs users in through. Required in production (the start
     * fails without it); in dev and test it is the realm Dev Services for Keycloak import.
     */
    Optional<String> realm();

    /**
     * Whether a created user's e-mail address counts as verified. WordPress trusted the addresses it kept, and
     * QuarkWord reads only verified addresses from tokens.
     */
    @WithDefault("true")
    boolean emailVerified();
}

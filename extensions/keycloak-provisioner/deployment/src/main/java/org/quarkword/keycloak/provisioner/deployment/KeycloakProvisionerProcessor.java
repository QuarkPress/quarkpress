package org.quarkword.keycloak.provisioner.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.keycloak.provisioner.runtime.AdminClientCheck;
import org.quarkword.keycloak.provisioner.runtime.KeycloakProvisionerConfig;
import org.quarkword.keycloak.provisioner.runtime.KeycloakUserProvisioner;

class KeycloakProvisionerProcessor {

    static final String FEATURE = "quarkword-keycloak-provisioner";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of(
                "keycloak-provisioner", "quarkword.keycloak-provisioner", KeycloakProvisionerConfig.class);
    }

    /** The provisioner replaces security's default bean, also when nothing injects it by type. */
    @BuildStep
    AdditionalBeanBuildItem beans() {
        return AdditionalBeanBuildItem.unremovableOf(KeycloakUserProvisioner.class);
    }

    /** The start-up check of the admin client's settings. */
    @BuildStep
    AdditionalBeanBuildItem check() {
        return AdditionalBeanBuildItem.unremovableOf(AdminClientCheck.class);
    }
}

package org.quarkpress.keycloak.provisioner.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.keycloak.provisioner.runtime.AdminClientCheck;
import org.quarkpress.keycloak.provisioner.runtime.KeycloakProvisionerConfig;
import org.quarkpress.keycloak.provisioner.runtime.KeycloakUserProvisioner;

class KeycloakProvisionerProcessor {

    static final String FEATURE = "quarkpress-keycloak-provisioner";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of(
                "keycloak-provisioner", "quarkpress.keycloak-provisioner", KeycloakProvisionerConfig.class);
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

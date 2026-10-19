package org.quarkpress.security.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import java.util.List;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkpress.security.UserProfileProvisioner;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.DevConfig;
import org.quarkpress.security.runtime.SecurityConfig;

class SecurityProcessor {

    static final String FEATURE = "quarkpress-security";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("security", 3);
    }

    @BuildStep
    List<QuarkPressConfigKeysBuildItem> configKeys() {
        return List.of(
                QuarkPressConfigKeysBuildItem.of("quarkpress.security", SecurityConfig.class),
                QuarkPressConfigKeysBuildItem.of("quarkpress.dev", DevConfig.class));
    }

    @BuildStep
    SiteScopedTableBuildItem membershipTable() {
        return new SiteScopedTableBuildItem("qp_site_membership");
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(UserProfileProvisioner.class, UserProfiles.class);
    }
}

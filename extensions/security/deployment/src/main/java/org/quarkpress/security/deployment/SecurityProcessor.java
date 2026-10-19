package org.quarkpress.security.deployment;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.quarkpress.core.RoleDefinition;
import org.quarkpress.core.deployment.spi.CapabilityBuildItem;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.RoleBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.Roles;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.UserProfileProvisioner;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.BuiltInRoles;
import org.quarkpress.security.runtime.CapabilityGuard;
import org.quarkpress.security.runtime.DevConfig;
import org.quarkpress.security.runtime.RoleTables;
import org.quarkpress.security.runtime.SecurityConfig;
import org.quarkpress.security.runtime.SecurityRecorder;

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
        return UnremovableBeanBuildItem.beanTypes(
                UserProfileProvisioner.class,
                UserProfiles.class,
                Capabilities.class,
                SystemIdentity.class,
                SiteRoles.class,
                CapabilityGuard.class,
                Memberships.class);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    SyntheticBeanBuildItem roles(
            SecurityRecorder recorder, List<RoleBuildItem> roles, List<CapabilityBuildItem> capabilities) {
        List<RoleDefinition> registered = new ArrayList<>();
        for (RoleBuildItem role : roles) {
            registered.add(role.role());
        }
        Map<String, Set<String>> grants = new LinkedHashMap<>();
        for (CapabilityBuildItem capability : capabilities) {
            grants.computeIfAbsent(capability.capability(), name -> new TreeSet<>())
                    .addAll(capability.grantedToRoles());
        }
        List<RoleDefinition> defaults;
        try {
            defaults = RoleTables.defaults(BuiltInRoles.all(), registered, grants);
        } catch (IllegalArgumentException refused) {
            throw new IllegalStateException("QuarkPress: " + refused.getMessage(), refused);
        }
        return SyntheticBeanBuildItem.configure(Roles.class)
                .scope(Singleton.class)
                .unremovable()
                .supplier(recorder.roles(RoleTables.toJson(defaults).toString()))
                .done();
    }
}

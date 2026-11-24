package org.quarkword.security.deployment;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.oidc.TenantConfigResolver;
import io.quarkus.resteasy.reactive.spi.ExceptionMapperBuildItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jboss.jandex.DotName;
import org.quarkword.core.CapabilityDeniedException;
import org.quarkword.core.RoleDefinition;
import org.quarkword.core.deployment.spi.CapabilityBuildItem;
import org.quarkword.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.RoleBuildItem;
import org.quarkword.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkword.security.Capabilities;
import org.quarkword.security.Memberships;
import org.quarkword.security.Roles;
import org.quarkword.security.SiteRoles;
import org.quarkword.security.SystemIdentity;
import org.quarkword.security.UserImports;
import org.quarkword.security.UserProfileProvisioner;
import org.quarkword.security.UserProfiles;
import org.quarkword.security.runtime.BuiltInRoles;
import org.quarkword.security.runtime.CapabilityDeniedMapper;
import org.quarkword.security.runtime.CapabilityGuard;
import org.quarkword.security.runtime.DevConfig;
import org.quarkword.security.runtime.RoleTables;
import org.quarkword.security.runtime.SecurityConfig;
import org.quarkword.security.runtime.SecurityRecorder;

class SecurityProcessor {

    static final String FEATURE = "quarkword-security";

    static final DotName TENANT_CONFIG_RESOLVER = DotName.createSimple(TenantConfigResolver.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("security", 3);
    }

    @BuildStep
    List<QuarkWordConfigKeysBuildItem> configKeys() {
        return List.of(
                QuarkWordConfigKeysBuildItem.of("security", "quarkword.security", SecurityConfig.class),
                QuarkWordConfigKeysBuildItem.of("security", "quarkword.dev", DevConfig.class));
    }

    @BuildStep
    SiteScopedTableBuildItem membershipTable() {
        return new SiteScopedTableBuildItem("qw_site_membership");
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
                Memberships.class,
                UserImports.class);
    }

    /**
     * With Quarkus REST, a network capability the caller lacks answers 401 (anonymous, with the bearer challenge) or
     * 403 instead of 500. The mapper is a bean, so nothing needs reflection; priority 5001 lets an application's own
     * mapper at the default priority (5000) win.
     */
    @BuildStep
    void capabilityDeniedMapper(
            io.quarkus.deployment.Capabilities capabilities, BuildProducer<ExceptionMapperBuildItem> mappers) {
        if (capabilities.isPresent(Capability.REST)) {
            mappers.produce(new ExceptionMapperBuildItem(
                    CapabilityDeniedMapper.class.getName(), CapabilityDeniedException.class.getName(), 5001, true));
        }
    }

    /**
     * Refuses dynamic OIDC tenants: a {@code TenantConfigResolver}'s tenant built without an id carries the default
     * tenant's id, so its tokens cannot be told from the default tenant's, and users are keyed by the subject alone.
     */
    @BuildStep
    void refuseDynamicTenants(ValidationPhaseBuildItem validation, BuildProducer<ValidationErrorBuildItem> errors) {
        validation.getContext().beans().withBeanType(TENANT_CONFIG_RESOLVER).forEach(bean -> {
            String message = "QuarkWord: the TenantConfigResolver " + bean.getBeanClass()
                    + " adds dynamic OIDC tenants, whose tokens cannot be told from the default tenant's;"
                    + " QuarkWord users come from the default tenant only, and dynamic tenants are not supported";
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException(message)));
        });
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
            throw new IllegalStateException("QuarkWord: " + refused.getMessage(), refused);
        }
        return SyntheticBeanBuildItem.configure(Roles.class)
                .scope(Singleton.class)
                .unremovable()
                .supplier(recorder.roles(RoleTables.toJson(defaults).toString()))
                .done();
    }
}

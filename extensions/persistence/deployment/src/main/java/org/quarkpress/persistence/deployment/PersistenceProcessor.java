package org.quarkpress.persistence.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.persistence.NetworkOptionStore;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.QuarkPressPersistence;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.CurrentSiteIdSupplier;
import org.quarkpress.persistence.runtime.QuarkPressBuildTimeConfig;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteStampListener;

class PersistenceProcessor {

    static final String FEATURE = "quarkpress-persistence";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                SiteScope.class, NetworkScope.class, Options.class, NetworkOptionStore.class);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("persistence", 1);
    }

    /**
     * Hibernate creates the site filter's parameter supplier, the entity listener and the JSON column type
     * reflectively, also natively.
     */
    @BuildStep
    ReflectiveClassBuildItem isolationClasses() {
        return ReflectiveClassBuildItem.builder(
                        CurrentSiteIdSupplier.class, SiteStampListener.class, QuarkPressJsonJdbcType.class)
                .constructors()
                .methods()
                .reason(PersistenceProcessor.class.getName())
                .build();
    }

    /**
     * Checks the datasource settings. It produces an indexed class so that it runs before the index is built, and so
     * before Hibernate ORM and Flyway report the same problem in their own words.
     */
    @BuildStep
    AdditionalIndexedClassesBuildItem checkDatasource(QuarkPressBuildTimeConfig quarkpress) {
        // The db-kind is a Quarkus datasource key, not a QuarkPress one, so it is read from the raw configuration.
        Config config = ConfigProvider.getConfig();
        Optional<String> dbKind = config.getOptionalValue(DatasourceChecks.DB_KIND, String.class);
        if (dbKind.isEmpty()) {
            throw new ConfigurationException(DatasourceChecks.missingDatasource());
        }
        String vendor = quarkpress.persistence().migrationVendor();
        Optional<String> problem = DatasourceChecks.vendorProblem(dbKind.get(), vendor);
        if (problem.isPresent()) {
            throw new ConfigurationException(problem.get());
        }
        return new AdditionalIndexedClassesBuildItem(QuarkPressPersistence.class.getName());
    }

    @BuildStep
    void checkMigrationBands(
            List<MigrationLocationBuildItem> locations, BuildProducer<ValidationErrorBuildItem> errors) {
        for (String problem : MigrationBands.problems(locations)) {
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException(problem)));
        }
    }
}

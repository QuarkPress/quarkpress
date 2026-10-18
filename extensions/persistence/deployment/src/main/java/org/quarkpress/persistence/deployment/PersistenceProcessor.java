package org.quarkpress.persistence.deployment;

import io.quarkus.arc.deployment.BeanDiscoveryFinishedBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.InjectionPointInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.QuarkPressPersistence;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.CurrentSiteIdSupplier;
import org.quarkpress.persistence.runtime.PersistenceRecorder;
import org.quarkpress.persistence.runtime.QuarkPressBuildTimeConfig;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteStampListener;

class PersistenceProcessor {

    static final String FEATURE = "quarkpress-persistence";
    static final DotName STATELESS_SESSION = DotName.createSimple("org.hibernate.StatelessSession");
    static final String UNIT_PREFIX = "quarkus.hibernate-orm.\"" + QuarkPressPersistence.UNIT + "\".";
    static final DotName PERSISTENCE_UNIT = DotName.createSimple("io.quarkus.hibernate.orm.PersistenceUnit");

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                SiteScope.class, NetworkScope.class, Options.class, NetworkOptions.class, FeatureToggles.class);
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

    @BuildStep
    SiteScopedTableBuildItem optionTable() {
        return new SiteScopedTableBuildItem("qp_option");
    }

    @BuildStep
    void checkSiteIsolation(
            CombinedIndexBuildItem combined,
            List<SiteScopedTableBuildItem> tables,
            BuildProducer<ValidationErrorBuildItem> errors)
            throws IOException {
        IndexView index = combined.getIndex();
        for (String problem : SiteIsolationModel.problems(index)) {
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException("QuarkPress: " + problem)));
        }
        Set<String> tableNames = new TreeSet<>();
        for (SiteScopedTableBuildItem table : tables) {
            tableNames.add(table.table());
        }
        Set<String> entityNames = SiteIsolationModel.siteScopedEntityNames(index);
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<SiteIsolationScanner.Finding> findings =
                new ArrayList<>(SiteIsolationModel.queryAnnotations(index, tableNames, entityNames));
        for (ClassInfo type : index.getKnownClasses()) {
            String name = type.name().toString();
            if (!name.startsWith("org.quarkpress.")) {
                continue;
            }
            byte[] bytes = null;
            try (InputStream in = loader.getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in != null) {
                    bytes = in.readAllBytes();
                }
            }
            if (bytes == null) {
                errors.produce(new ValidationErrorBuildItem(new IllegalStateException("QuarkPress: cannot read the"
                        + " bytecode of " + name + " to check that it keeps to its site; the class must be loadable"
                        + " from the application class path")));
                continue;
            }
            try {
                findings.addAll(SiteIsolationScanner.scan(bytes, tableNames, entityNames));
            } catch (RuntimeException unreadable) {
                errors.produce(new ValidationErrorBuildItem(new IllegalStateException(
                        "QuarkPress: cannot read the bytecode of " + name + " to check that it keeps to its site",
                        unreadable)));
            }
        }
        if (!findings.isEmpty()) {
            errors.produce(
                    new ValidationErrorBuildItem(new IllegalStateException(SiteIsolationScanner.message(findings))));
        }
    }

    @BuildStep
    SiteAssociationsBuildItem siteAssociations(
            CombinedIndexBuildItem combined,
            BuildProducer<ValidationErrorBuildItem> errors,
            BuildProducer<ReflectiveClassBuildItem> reflective) {
        SiteAssociationModel.Result result = SiteAssociationModel.analyse(combined.getIndex());
        for (String problem : result.problems()) {
            errors.produce(new ValidationErrorBuildItem(new IllegalStateException(problem)));
        }
        if (!result.fieldsByClass().isEmpty()) {
            reflective.produce(ReflectiveClassBuildItem.builder(
                            result.fieldsByClass().keySet().toArray(String[]::new))
                    .fields()
                    .reason(PersistenceProcessor.class.getName())
                    .build());
        }
        return new SiteAssociationsBuildItem(result.fieldsByClass());
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void recordSiteAssociations(SiteAssociationsBuildItem associations, PersistenceRecorder recorder) {
        Map<String, List<String>> byClass = new HashMap<>();
        associations.fieldsByClass().forEach((type, fields) -> byClass.put(type, new ArrayList<>(fields)));
        recorder.siteAssociations(byClass);
    }

    @BuildStep
    void checkStatelessSessions(BeanDiscoveryFinishedBuildItem beans, BuildProducer<ValidationErrorBuildItem> errors) {
        for (InjectionPointInfo point : beans.getInjectionPoints()) {
            if (namesStatelessSession(point.getType()) && namesQuarkPressUnit(point)) {
                errors.produce(new ValidationErrorBuildItem(new IllegalStateException(
                        "QuarkPress: a StatelessSession for the quarkpress unit is not allowed: updates of"
                                + " detached objects bypass the stored-site snapshot and its DML is not filtered by"
                                + " site; inject the EntityManager instead (" + point.getTargetInfo() + ")")));
            }
        }
    }

    @BuildStep
    void checkCaching(BuildProducer<ValidationErrorBuildItem> errors) {
        Config config = ConfigProvider.getConfig();
        boolean caching = config.getOptionalValue(UNIT_PREFIX + "second-level-caching-enabled", Boolean.class)
                .orElse(false);
        if (caching) {
            errors.produce(new ValidationErrorBuildItem(
                    new IllegalStateException("QuarkPress: " + UNIT_PREFIX + "second-level-caching-enabled must stay"
                            + " false; cached rows and query results bypass the site filter")));
        }
        for (String property : List.of("hibernate.cache.use_second_level_cache", "hibernate.cache.use_query_cache")) {
            String key = UNIT_PREFIX + "unsupported-properties.\"" + property + "\"";
            boolean on = config.getOptionalValue(key, String.class)
                    .map(value -> value.trim().equalsIgnoreCase("true"))
                    .orElse(false);
            if (on) {
                errors.produce(new ValidationErrorBuildItem(new IllegalStateException("QuarkPress: " + key
                        + " must not be true; cached rows and query results bypass the site filter")));
            }
        }
    }

    /** A StatelessSession, or an Instance or Provider of one. */
    private static boolean namesStatelessSession(Type type) {
        if (type.name().equals(STATELESS_SESSION)) {
            return true;
        }
        if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            for (Type argument : type.asParameterizedType().arguments()) {
                if (argument.name().equals(STATELESS_SESSION)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean namesQuarkPressUnit(InjectionPointInfo point) {
        for (AnnotationInstance qualifier : point.getRequiredQualifiers()) {
            if (qualifier.name().equals(PERSISTENCE_UNIT)
                    && QuarkPressPersistence.UNIT.equals(qualifier.value().asString())) {
                return true;
            }
        }
        return false;
    }
}

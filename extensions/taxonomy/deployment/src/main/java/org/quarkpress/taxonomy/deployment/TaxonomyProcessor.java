package org.quarkpress.taxonomy.deployment;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkpress.core.deployment.spi.TaxonomyBuildItem;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.runtime.BuiltInTaxonomies;
import org.quarkpress.taxonomy.runtime.JpaTerms;
import org.quarkpress.taxonomy.runtime.TaxonomyJson;
import org.quarkpress.taxonomy.runtime.TaxonomyRecorder;
import org.quarkpress.taxonomy.runtime.TermCapabilityFacts;
import org.quarkpress.taxonomy.runtime.TermLookups;

class TaxonomyProcessor {

    static final String FEATURE = "quarkpress-taxonomy";
    static final DotName TAXONOMY = DotName.createSimple(Taxonomy.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                TermCapabilityFacts.class, TermLookups.class, Terms.class, JpaTerms.class);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("taxonomy", 5);
    }

    @BuildStep
    List<SiteScopedTableBuildItem> siteScopedTables() {
        return List.of(new SiteScopedTableBuildItem("qp_term"), new SiteScopedTableBuildItem("qp_term_assignment"));
    }

    /**
     * The registry: built-in taxonomies first, then the taxonomies of build items and annotated classes together in key
     * order. A key registered twice and an invalid registration fail the build.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    SyntheticBeanBuildItem registry(
            TaxonomyRecorder recorder, List<TaxonomyBuildItem> items, CombinedIndexBuildItem index) {
        List<TaxonomyDefinition> taxonomies = new ArrayList<>(BuiltInTaxonomies.all());
        List<TaxonomyDefinition> registered = new ArrayList<>();
        items.forEach(item -> registered.add(item.definition()));
        registered.addAll(annotated(index.getIndex()));
        registered.sort(Comparator.comparing(TaxonomyDefinition::key));
        taxonomies.addAll(registered);
        Set<String> seen = new HashSet<>();
        for (TaxonomyDefinition taxonomy : taxonomies) {
            if (!seen.add(taxonomy.key())) {
                throw new IllegalStateException(
                        "QuarkPress: the taxonomy " + taxonomy.key() + " is registered more than once");
            }
        }
        return SyntheticBeanBuildItem.configure(Taxonomies.class)
                .scope(Singleton.class)
                .unremovable()
                .supplier(recorder.taxonomies(TaxonomyJson.write(taxonomies)))
                .done();
    }

    private static List<TaxonomyDefinition> annotated(IndexView index) {
        List<TaxonomyDefinition> taxonomies = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(TAXONOMY)) {
            try {
                taxonomies.add(definition(annotation));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "QuarkPress: @Taxonomy on " + annotation.target() + ": " + invalid.getMessage(), invalid);
            }
        }
        return taxonomies;
    }

    /** The definition an annotation gives; {@code showUi} is passed to the builder only when given. */
    private static TaxonomyDefinition definition(AnnotationInstance annotation) {
        String key = annotation.value("key").asString();
        TaxonomyDefinition.Builder builder = TaxonomyDefinition.builder(key, strings(annotation, "objectTypes"))
                .hierarchical(bool(annotation, "hierarchical", false))
                .publicly(bool(annotation, "publicly", true))
                .description(text(annotation, "description"))
                .sort(bool(annotation, "sort", false));
        if (annotation.value("showUi") != null) {
            builder.showUi(bool(annotation, "showUi", true));
        }
        Map<String, String> labels = new LinkedHashMap<>();
        if (!text(annotation, "name").isEmpty()) {
            labels.put("name", text(annotation, "name"));
        }
        if (!text(annotation, "singularName").isEmpty()) {
            labels.put("singular_name", text(annotation, "singularName"));
        }
        for (String entry : strings(annotation, "labels")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException(
                        "a label of @Taxonomy " + key + " must read key=value: '" + entry + "'");
            }
            labels.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        builder.labels(labels);
        if (!text(annotation, "restBase").isEmpty()) {
            builder.showInRest(true).restBase(text(annotation, "restBase"));
        }
        if (!text(annotation, "defaultTerm").isEmpty()) {
            builder.defaultTerm(text(annotation, "defaultTerm"));
        }
        return builder.build();
    }

    private static boolean bool(AnnotationInstance annotation, String name, boolean fallback) {
        AnnotationValue value = annotation.value(name);
        return value == null ? fallback : value.asBoolean();
    }

    private static String text(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? "" : value.asString();
    }

    private static List<String> strings(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? List.of() : List.of(value.asStringArray());
    }
}

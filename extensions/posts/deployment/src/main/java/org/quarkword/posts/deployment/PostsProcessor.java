package org.quarkword.posts.deployment;

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
import org.quarkword.core.PostStatusDefinition;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkword.core.deployment.spi.PostStatusBuildItem;
import org.quarkword.core.deployment.spi.PostTypeBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkword.posts.PostAuthors;
import org.quarkword.posts.PostMeta;
import org.quarkword.posts.PostPermissions;
import org.quarkword.posts.PostStatuses;
import org.quarkword.posts.PostType;
import org.quarkword.posts.PostTypes;
import org.quarkword.posts.Posts;
import org.quarkword.posts.runtime.BuiltInPostStatuses;
import org.quarkword.posts.runtime.BuiltInPostTypes;
import org.quarkword.posts.runtime.PostCapabilityFacts;
import org.quarkword.posts.runtime.PostsConfig;
import org.quarkword.posts.runtime.PostsRecorder;
import org.quarkword.posts.runtime.RegistryJson;

class PostsProcessor {

    static final String FEATURE = "quarkword-posts";

    static final DotName POST_TYPE = DotName.createSimple(PostType.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                PostCapabilityFacts.class, PostMeta.class, Posts.class, PostPermissions.class, PostAuthors.class);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("posts", 4);
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("posts", "quarkword.posts", PostsConfig.class);
    }

    @BuildStep
    List<SiteScopedTableBuildItem> siteScopedTables() {
        return List.of(
                new SiteScopedTableBuildItem("qw_post"),
                new SiteScopedTableBuildItem("qw_post_lock"),
                new SiteScopedTableBuildItem("qw_post_token"));
    }

    /**
     * The registries: built-in types first, then the types of build items and annotated classes together in key
     * order; built-in statuses first, then the statuses of build items in name order. A key registered twice and an
     * invalid registration fail the build.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    List<SyntheticBeanBuildItem> registries(
            PostsRecorder recorder,
            List<PostTypeBuildItem> typeItems,
            List<PostStatusBuildItem> statusItems,
            CombinedIndexBuildItem index) {
        List<PostTypeDefinition> types = new ArrayList<>(BuiltInPostTypes.all());
        List<PostTypeDefinition> registered = new ArrayList<>();
        typeItems.forEach(item -> registered.add(item.definition()));
        registered.addAll(annotatedTypes(index.getIndex()));
        registered.sort(Comparator.comparing(PostTypeDefinition::key));
        types.addAll(registered);
        requireDistinct(types.stream().map(PostTypeDefinition::key).toList(), "post type");

        List<PostStatusDefinition> statuses = new ArrayList<>(BuiltInPostStatuses.all());
        List<PostStatusDefinition> added = new ArrayList<>();
        statusItems.forEach(item -> added.add(item.definition()));
        added.sort(Comparator.comparing(PostStatusDefinition::name));
        statuses.addAll(added);
        requireDistinct(statuses.stream().map(PostStatusDefinition::name).toList(), "post status");

        return List.of(
                SyntheticBeanBuildItem.configure(PostTypes.class)
                        .scope(Singleton.class)
                        .unremovable()
                        .supplier(recorder.postTypes(RegistryJson.writeTypes(types)))
                        .done(),
                SyntheticBeanBuildItem.configure(PostStatuses.class)
                        .scope(Singleton.class)
                        .unremovable()
                        .supplier(recorder.postStatuses(RegistryJson.writeStatuses(statuses)))
                        .done());
    }

    private static List<PostTypeDefinition> annotatedTypes(IndexView index) {
        List<PostTypeDefinition> types = new ArrayList<>();
        for (AnnotationInstance annotation : index.getAnnotations(POST_TYPE)) {
            String key = annotation.value("key").asString();
            try {
                types.add(annotatedType(key, annotation));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalStateException(
                        "QuarkWord: @PostType on " + annotation.target() + ": " + invalid.getMessage(), invalid);
            }
        }
        return types;
    }

    /**
     * The definition an annotation gives. An attribute whose WordPress default is derived (excludeFromSearch, showUi,
     * mapMetaCap) is passed to the builder only when given; the others have the builder's defaults.
     */
    private static PostTypeDefinition annotatedType(String key, AnnotationInstance annotation) {
        PostTypeDefinition.Builder builder = PostTypeDefinition.builder(key)
                .hierarchical(booleanValue(annotation, "hierarchical"))
                .publicly(booleanValue(annotation, "publicly"))
                .capabilityType(stringValue(annotation, "capabilityType", "post"))
                .description(stringValue(annotation, "description", ""))
                .hasArchive(booleanValue(annotation, "hasArchive"));
        if (annotation.value("excludeFromSearch") != null) {
            builder.excludeFromSearch(booleanValue(annotation, "excludeFromSearch"));
        }
        if (annotation.value("showUi") != null) {
            builder.showUi(booleanValue(annotation, "showUi"));
        }
        if (annotation.value("mapMetaCap") != null) {
            builder.mapMetaCap(booleanValue(annotation, "mapMetaCap"));
        }
        Map<String, String> labels = new LinkedHashMap<>();
        String name = stringValue(annotation, "name", "");
        if (!name.isEmpty()) {
            labels.put("name", name);
        }
        String singular = stringValue(annotation, "singularName", "");
        if (!singular.isEmpty()) {
            labels.put("singular_name", singular);
        }
        for (String entry : stringsValue(annotation, "labels")) {
            int equals = entry.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException(
                        "a label of @PostType " + key + " must read key=value: '" + entry + "'");
            }
            labels.put(entry.substring(0, equals), entry.substring(equals + 1));
        }
        builder.labels(labels).supports(stringsValue(annotation, "supports"));
        String restBase = stringValue(annotation, "restBase", "");
        if (!restBase.isEmpty()) {
            builder.showInRest(true).restBase(restBase);
        }
        return builder.build();
    }

    private static boolean booleanValue(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value != null && value.asBoolean();
    }

    private static String stringValue(AnnotationInstance annotation, String name, String fallback) {
        AnnotationValue value = annotation.value(name);
        return value == null ? fallback : value.asString();
    }

    private static List<String> stringsValue(AnnotationInstance annotation, String name) {
        AnnotationValue value = annotation.value(name);
        return value == null ? List.of() : List.of(value.asStringArray());
    }

    private static void requireDistinct(List<String> keys, String what) {
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw new IllegalStateException("QuarkWord: the " + what + " " + key + " is registered more than once");
            }
        }
    }
}

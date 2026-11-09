package org.quarkpress.restwp.deployment;

import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveDeploymentBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveResourceMethodEntriesBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestWpV2Feature;
import org.quarkpress.restwp.SettingsProvider;
import org.quarkpress.restwp.SettingsRegistry;
import org.quarkpress.restwp.runtime.PreviewPage;
import org.quarkpress.restwp.runtime.RestEntryResource;
import org.quarkpress.restwp.runtime.RestPaths;
import org.quarkpress.restwp.runtime.RestWpConfig;
import org.quarkpress.restwp.runtime.RestWpRecorder;

class RestWpV2Processor {

    /** The largest form attribute size Quarkus accepts: it reads the setting as an {@code int}. */
    static final int FORM_ATTRIBUTE_LIMIT = Integer.MAX_VALUE;

    static final String FEATURE = "quarkpress-rest-wp-v2";

    /** The capability the revisions module provides. */
    static final String REVISIONS = "org.quarkpress.revisions";

    /** The revisions module's exception, which the error mapping looks up by name. */
    static final String REVISION_EXCEPTION = "org.quarkpress.revisions.RevisionException";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                RestWpV2Feature.KEY,
                "rest-wp-v2",
                true,
                "Serves WordPress's REST API (wp/v2) for the site; when off, every REST URL of the site answers 404"
                        + " rest_no_route");
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("rest-wp-v2", "quarkpress.rest-wp-v2", RestWpConfig.class);
    }

    /**
     * A multipart body's text parts are request parameters, which PHP bounds only by the body's size: Quarkus's limit
     * on one form attribute ({@code quarkus.http.limits.max-form-attribute-size}, 2048 bytes of its own) defaults to
     * the largest value Quarkus reads ({@value #FORM_ATTRIBUTE_LIMIT} bytes), so that the body limit
     * ({@code quarkus.http.limits.max-body-size}) is what bounds them. An application's own setting still wins.
     */
    @BuildStep
    RunTimeConfigurationDefaultBuildItem formAttributeLimit() {
        return new RunTimeConfigurationDefaultBuildItem(
                "quarkus.http.limits.max-form-attribute-size", Integer.toString(FORM_ATTRIBUTE_LIMIT));
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                RestWpConfig.class, RestRouteProvider.class, SettingsProvider.class, SettingsRegistry.class);
    }

    /**
     * The error mapping finds out whether the optional revisions module is present by looking its exception up by name,
     * which a native executable answers only for a class registered for reflection (the class alone: no constructor,
     * method or field is used reflectively).
     */
    @BuildStep
    void revisionException(Capabilities capabilities, BuildProducer<ReflectiveClassBuildItem> reflective) {
        if (capabilities.isPresent(REVISIONS)) {
            reflective.produce(ReflectiveClassBuildItem.builder(REVISION_EXCEPTION)
                    .constructors(false)
                    .methods(false)
                    .fields(false)
                    .reason(RestWpV2Processor.class.getName())
                    .build());
        }
    }

    /**
     * The beans typed on the revisions module (its revisions and autosaves routes, their field preparation, the post
     * links' revision history and the preview page's autosave), named here as texts so that this processor loads
     * without that module.
     */
    static final List<String> REVISION_BEANS = List.of(
            "org.quarkpress.restwp.runtime.wpv2.RevisionRoutes",
            "org.quarkpress.restwp.runtime.wpv2.RevisionFields",
            "org.quarkpress.restwp.runtime.wpv2.RevisionHistoryReads",
            "org.quarkpress.restwp.runtime.wpv2.AutosaveRoutes",
            "org.quarkpress.restwp.runtime.LatestAutosaves");

    /**
     * Without the revisions module, the beans typed on it are left out of the application: the posts controller then
     * registers no revisions route, and no class naming a type of the absent module is loaded.
     */
    @BuildStep
    void withoutRevisions(Capabilities capabilities, BuildProducer<ExcludedTypeBuildItem> excluded) {
        if (!capabilities.isPresent(REVISIONS)) {
            REVISION_BEANS.forEach(type -> excluded.produce(new ExcludedTypeBuildItem(type)));
        }
    }

    /** The capability the media module provides. */
    static final String MEDIA = "org.quarkpress.media";

    /**
     * The beans typed on the media module (the media routes, their attachment reads and the attachment's fields),
     * named here as texts so that this processor loads without that module.
     */
    static final List<String> MEDIA_BEANS = List.of(
            "org.quarkpress.restwp.runtime.wpv2.MediaController",
            "org.quarkpress.restwp.runtime.wpv2.MediaReads",
            "org.quarkpress.restwp.runtime.wpv2.MediaFields",
            "org.quarkpress.restwp.runtime.wpv2.MediaLinks",
            "org.quarkpress.restwp.runtime.wpv2.MediaCollection");

    /**
     * Without the media module, the beans typed on it are left out of the application: no media route is
     * registered, and no class naming a type of the absent module is loaded.
     */
    @BuildStep
    void withoutMedia(Capabilities capabilities, BuildProducer<ExcludedTypeBuildItem> excluded) {
        if (!capabilities.isPresent(MEDIA)) {
            MEDIA_BEANS.forEach(type -> excluded.produce(new ExcludedTypeBuildItem(type)));
        }
    }

    /** The REST request filter never treats a path under the non-application root as a REST path. */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void nonApplicationRoot(NonApplicationRootPathBuildItem root, RestWpRecorder recorder) {
        recorder.nonApplicationRoot(root.getNonApplicationRootPath());
    }

    /**
     * Where REST requests and front-end page requests are re-routed: the entry resource's and the preview page's paths
     * below the REST application path (from {@code quarkus.rest.path} or an {@code @ApplicationPath}) and the HTTP
     * root path. The build fails when Quarkus REST does not deploy either resource, as no REST request (or no
     * front-end page) could then be served.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void entryPath(
            HttpRootPathBuildItem httpRoot,
            Optional<ResteasyReactiveDeploymentBuildItem> deployment,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> methods,
            RestWpRecorder recorder) {
        requireDeployed(deployment, methods, RestEntryResource.class.getName(), "no REST request can be served");
        requireDeployed(deployment, methods, PreviewPage.class.getName(), "no front-end page can be served");
        String root = httpRoot.getRootPath();
        String application = deployment.get().getApplicationPath();
        String entry;
        String preview;
        try {
            entry = RestPaths.entryPath(root, application);
            preview = RestPaths.previewPath(root, application);
        } catch (IllegalArgumentException unusable) {
            throw new ConfigurationException("quarkpress-rest-wp-v2: " + unusable.getMessage());
        }
        recorder.entryPath(entry);
        recorder.previewPath(preview);
    }

    private static void requireDeployed(
            Optional<ResteasyReactiveDeploymentBuildItem> deployment,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> methods,
            String resource,
            String consequence) {
        boolean deployed = deployment.isPresent()
                && methods.isPresent()
                && methods.get().getEntries().stream()
                        .anyMatch(entry ->
                                entry.getActualClassInfo().name().toString().equals(resource));
        if (!deployed) {
            throw new ConfigurationException("quarkpress-rest-wp-v2: Quarkus REST does not deploy " + resource + ", so "
                    + consequence + "; do not exclude it from the build");
        }
    }
}

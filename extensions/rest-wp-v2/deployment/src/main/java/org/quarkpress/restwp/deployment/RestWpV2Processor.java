package org.quarkpress.restwp.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveDeploymentBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveResourceMethodEntriesBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestWpV2Feature;
import org.quarkpress.restwp.SettingsProvider;
import org.quarkpress.restwp.SettingsRegistry;
import org.quarkpress.restwp.runtime.RestEntryResource;
import org.quarkpress.restwp.runtime.RestPaths;
import org.quarkpress.restwp.runtime.RestWpConfig;
import org.quarkpress.restwp.runtime.RestWpRecorder;

class RestWpV2Processor {

    static final String FEATURE = "quarkpress-rest-wp-v2";

    /** The capability the multisite module provides. */
    static final String MULTISITE = "org.quarkpress.multisite";

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

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                RestWpConfig.class, RestRouteProvider.class, SettingsProvider.class, SettingsRegistry.class);
    }

    /**
     * Whether the application is a network, which decides WordPress's settings: the multisite module is present and its
     * build-time switch {@code quarkpress.multisite.enabled} is on.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void installMode(Capabilities capabilities, RestWpRecorder recorder) {
        boolean multisite = capabilities.isPresent(MULTISITE)
                && ConfigProvider.getConfig()
                        .getOptionalValue("quarkpress.multisite.enabled", Boolean.class)
                        .orElse(false);
        recorder.multisite(multisite);
    }

    /** The REST request filter never treats a path under the non-application root as a REST path. */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void nonApplicationRoot(NonApplicationRootPathBuildItem root, RestWpRecorder recorder) {
        recorder.nonApplicationRoot(root.getNonApplicationRootPath());
    }

    /**
     * Where REST requests are re-routed: the entry resource's path below the REST application path (from
     * {@code quarkus.rest.path} or an {@code @ApplicationPath}) and the HTTP root path. The build fails when Quarkus
     * REST does not deploy the entry resource, as no REST request could then be served.
     */
    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    void entryPath(
            HttpRootPathBuildItem httpRoot,
            Optional<ResteasyReactiveDeploymentBuildItem> deployment,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> methods,
            RestWpRecorder recorder) {
        String entryClass = RestEntryResource.class.getName();
        boolean deployed = deployment.isPresent()
                && methods.isPresent()
                && methods.get().getEntries().stream()
                        .anyMatch(entry ->
                                entry.getActualClassInfo().name().toString().equals(entryClass));
        if (!deployed) {
            throw new ConfigurationException("quarkpress-rest-wp-v2: Quarkus REST does not deploy " + entryClass
                    + ", so no REST request can be served; do not exclude it from the build");
        }
        String path;
        try {
            path = RestPaths.entryPath(httpRoot.getRootPath(), deployment.get().getApplicationPath());
        } catch (IllegalArgumentException unusable) {
            throw new ConfigurationException("quarkpress-rest-wp-v2: " + unusable.getMessage());
        }
        recorder.entryPath(path);
    }
}

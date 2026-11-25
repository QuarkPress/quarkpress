package org.quarkword.front.deployment;

import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveDeploymentBuildItem;
import io.quarkus.resteasy.reactive.server.deployment.ResteasyReactiveResourceMethodEntriesBuildItem;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.quarkus.vertx.http.deployment.HttpRootPathBuildItem;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import java.util.Optional;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.front.FrontFeature;
import org.quarkword.front.FrontRenderer;
import org.quarkword.front.FrontResolver;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.Permalinks;
import org.quarkword.front.runtime.CanonicalRedirects;
import org.quarkword.front.runtime.DefaultFrontResolver;
import org.quarkword.front.runtime.DefaultPermalinks;
import org.quarkword.front.runtime.FallbackPage;
import org.quarkword.front.runtime.FrontConfig;
import org.quarkword.front.runtime.FrontHeaders;
import org.quarkword.front.runtime.FrontLinkBuilder;
import org.quarkword.front.runtime.FrontPage;
import org.quarkword.front.runtime.FrontRecorder;
import org.quarkword.front.runtime.FrontRoute;
import org.quarkword.front.runtime.FrontSiteGate;
import org.quarkword.front.runtime.OldSlugRedirects;
import org.quarkword.front.runtime.PagePaths;
import org.quarkword.front.runtime.PostPassCookies;
import org.quarkword.front.runtime.PostPassEndpoint;
import org.quarkword.front.runtime.PreviewViewer;
import org.quarkword.front.runtime.RequestFrontUrls;
import org.quarkword.posts.LinkBuilder;

class FrontProcessor {

    static final String FEATURE = "quarkword-front";

    /** The per-site switch: off, the site's public pages are not served by this module. */
    static final String SITE_FEATURE = FrontFeature.KEY;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                FrontUrls.class,
                RequestFrontUrls.class,
                Permalinks.class,
                DefaultPermalinks.class,
                FrontLinkBuilder.class,
                LinkBuilder.class,
                PagePaths.class,
                FrontResolver.class,
                DefaultFrontResolver.class,
                CanonicalRedirects.class,
                OldSlugRedirects.class,
                FrontHeaders.class,
                FrontSiteGate.class,
                FrontRenderer.class,
                FallbackPage.class,
                PreviewViewer.class,
                PostPassCookies.class,
                PostPassEndpoint.class);
    }

    /** The capability the media module provides. */
    static final String MEDIA = "org.quarkword.media";

    /** The beans typed on the media module, named as texts so that this processor loads without that module. */
    static final String MEDIA_BEAN = "org.quarkword.front.runtime.MediaAttachmentFiles";

    /** Without the media module, the attachment files bean is left out: attachments have no file to redirect to. */
    @BuildStep
    void withoutMedia(Capabilities capabilities, BuildProducer<ExcludedTypeBuildItem> excluded) {
        if (!capabilities.isPresent(MEDIA)) {
            excluded.produce(new ExcludedTypeBuildItem(MEDIA_BEAN));
        }
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                SITE_FEATURE,
                "front",
                true,
                "Serves the site's public pages: permalinks, archives, searches and the redirects WordPress makes;"
                        + " when off, the site's front-end requests are not answered by this module");
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("front", "quarkword.front", FrontConfig.class);
    }

    /**
     * Where front-end requests are re-routed: the page resource's path below the REST application path (from
     * {@code quarkus.rest.path} or an {@code @ApplicationPath}) and the HTTP root path. The build fails when Quarkus
     * REST does not deploy the resource, as no front-end page could then be served.
     */
    @BuildStep
    FrontEntryPathBuildItem entryPath(
            HttpRootPathBuildItem httpRoot,
            Optional<ResteasyReactiveDeploymentBuildItem> deployment,
            Optional<ResteasyReactiveResourceMethodEntriesBuildItem> methods) {
        String resource = FrontPage.class.getName();
        boolean deployed = deployment.isPresent()
                && methods.isPresent()
                && methods.get().getEntries().stream()
                        .anyMatch(entry ->
                                entry.getActualClassInfo().name().toString().equals(resource));
        if (!deployed) {
            throw new ConfigurationException("quarkword-front: Quarkus REST does not deploy " + resource
                    + ", so no front-end page can be served; do not exclude it from the build");
        }
        String path = join(
                join(httpRoot.getRootPath(), deployment.get().getApplicationPath()),
                FrontPage.PATH + "/" + FrontPage.PAGE);
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0 || path.indexOf('*') >= 0 || path.indexOf(':') >= 0) {
            throw new ConfigurationException("quarkword-front: the front-end page path " + path
                    + " (quarkus.http.root-path " + httpRoot.getRootPath() + ", REST application path "
                    + deployment.get().getApplicationPath() + ") cannot be re-routed to; use a plain path");
        }
        return new FrontEntryPathBuildItem(path);
    }

    /**
     * The catch-all route on every path of the application, after Quarkus REST's routes and the static resources: a
     * {@code GET} or {@code HEAD} that nothing else answered is re-routed to the page resource, a {@code POST} to a
     * {@code wp-login.php} path to its {@code postpass} method.
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    RouteBuildItem route(
            FrontEntryPathBuildItem entry, NonApplicationRootPathBuildItem nonApplication, FrontRecorder recorder) {
        return RouteBuildItem.builder()
                .orderedRoute("/*", FrontRoute.ORDER)
                .handler(recorder.handler(entry.path(), nonApplication.getNonApplicationRootPath()))
                .build();
    }

    private static String join(String parent, String child) {
        String left = trimSlashes(parent.strip());
        String right = trimSlashes(child.strip());
        if (left.isEmpty()) {
            return "/" + right;
        }
        return right.isEmpty() ? "/" + left : "/" + left + "/" + right;
    }

    private static String trimSlashes(String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}

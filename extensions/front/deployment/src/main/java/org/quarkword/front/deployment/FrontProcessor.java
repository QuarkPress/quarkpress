package org.quarkword.front.deployment;

import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.front.FrontFeature;
import org.quarkword.front.FrontResolver;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.Permalinks;
import org.quarkword.front.runtime.CanonicalRedirects;
import org.quarkword.front.runtime.DefaultFrontResolver;
import org.quarkword.front.runtime.DefaultPermalinks;
import org.quarkword.front.runtime.FrontConfig;
import org.quarkword.front.runtime.FrontHeaders;
import org.quarkword.front.runtime.FrontLinkBuilder;
import org.quarkword.front.runtime.FrontSiteGate;
import org.quarkword.front.runtime.OldSlugRedirects;
import org.quarkword.front.runtime.PagePaths;
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
                FrontSiteGate.class);
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
}

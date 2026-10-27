package org.quarkpress.scheduling.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.scheduling.ScheduledPosts;
import org.quarkpress.scheduling.SchedulingFeature;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.DuePosts;
import org.quarkpress.scheduling.runtime.Housekeeping;
import org.quarkpress.scheduling.runtime.PublishPoll;
import org.quarkpress.scheduling.runtime.ScheduledPostsBean;
import org.quarkpress.scheduling.runtime.SchedulingConfig;
import org.quarkpress.scheduling.runtime.SchedulingDisabled;
import org.quarkpress.scheduling.runtime.SchedulingStatusBean;

class SchedulingProcessor {

    static final String FEATURE = "quarkpress-scheduling";

    /** The per-site switch: off, a site's scheduled posts wait and nothing of the site is purged. */
    static final String SITE_FEATURE = SchedulingFeature.KEY;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                SchedulingStatus.class,
                SchedulingStatusBean.class,
                PublishPoll.class,
                Housekeeping.class,
                DuePosts.class,
                SchedulingDisabled.class,
                ScheduledPosts.class,
                ScheduledPostsBean.class);
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                SITE_FEATURE,
                "scheduling",
                true,
                "Publishes scheduled posts when their time comes and purges the trash and old auto-drafts; when off,"
                        + " the site's scheduled posts wait and nothing of the site is purged");
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("scheduling", "quarkpress.scheduling", SchedulingConfig.class);
    }
}

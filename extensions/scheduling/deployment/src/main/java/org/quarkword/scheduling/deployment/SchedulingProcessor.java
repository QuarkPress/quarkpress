package org.quarkword.scheduling.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.scheduling.ScheduledPosts;
import org.quarkword.scheduling.SchedulingFeature;
import org.quarkword.scheduling.SchedulingStatus;
import org.quarkword.scheduling.runtime.DuePosts;
import org.quarkword.scheduling.runtime.Housekeeping;
import org.quarkword.scheduling.runtime.PublishPoll;
import org.quarkword.scheduling.runtime.ScheduledPostsBean;
import org.quarkword.scheduling.runtime.SchedulingConfig;
import org.quarkword.scheduling.runtime.SchedulingDisabled;
import org.quarkword.scheduling.runtime.SchedulingStatusBean;

class SchedulingProcessor {

    static final String FEATURE = "quarkword-scheduling";

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
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("scheduling", "quarkword.scheduling", SchedulingConfig.class);
    }
}

package org.quarkpress.revisions.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.revisions.runtime.JpaRevisions;
import org.quarkpress.revisions.runtime.PostRevisionObserver;
import org.quarkpress.revisions.runtime.RevisionEvents;
import org.quarkpress.revisions.runtime.RevisionPolicy;
import org.quarkpress.revisions.runtime.RevisionRepository;
import org.quarkpress.revisions.runtime.RevisionWrites;
import org.quarkpress.revisions.runtime.RevisionsConfig;

class RevisionsProcessor {

    static final String FEATURE = "quarkpress-revisions";

    /** The per-site switch: off, a site saves and lists no revisions (autosaves still work). */
    static final String SITE_FEATURE = RevisionPolicy.SITE_FEATURE;

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                Revisions.class,
                JpaRevisions.class,
                PostRevisionObserver.class,
                RevisionWrites.class,
                RevisionEvents.class,
                RevisionRepository.class,
                RevisionPolicy.class);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("revisions", 6);
    }

    @BuildStep
    SiteScopedTableBuildItem siteScopedTables() {
        return new SiteScopedTableBuildItem("qp_revision");
    }

    @BuildStep
    SiteFeatureBuildItem siteFeature() {
        return new SiteFeatureBuildItem(
                SITE_FEATURE,
                "revisions",
                true,
                "Keeps revisions of posts; when off, no new revisions are saved and none are listed (autosaves still"
                        + " work)");
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("quarkpress.revisions", RevisionsConfig.class);
    }
}

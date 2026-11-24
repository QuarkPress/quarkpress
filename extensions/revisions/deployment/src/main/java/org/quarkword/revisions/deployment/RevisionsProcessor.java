package org.quarkword.revisions.deployment;

import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkword.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkword.revisions.Revisions;
import org.quarkword.revisions.runtime.JpaRevisions;
import org.quarkword.revisions.runtime.PostRevisionObserver;
import org.quarkword.revisions.runtime.RevisionEvents;
import org.quarkword.revisions.runtime.RevisionPolicy;
import org.quarkword.revisions.runtime.RevisionQueries;
import org.quarkword.revisions.runtime.RevisionRepository;
import org.quarkword.revisions.runtime.RevisionWrites;
import org.quarkword.revisions.runtime.RevisionsConfig;

class RevisionsProcessor {

    static final String FEATURE = "quarkword-revisions";

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
                RevisionQueries.class,
                RevisionPolicy.class);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("revisions", 6);
    }

    @BuildStep
    SiteScopedTableBuildItem siteScopedTables() {
        return new SiteScopedTableBuildItem("qw_revision");
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
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("revisions", "quarkword.revisions", RevisionsConfig.class);
    }
}

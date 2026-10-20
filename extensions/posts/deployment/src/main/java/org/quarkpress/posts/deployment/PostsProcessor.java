package org.quarkpress.posts.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import java.util.List;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;
import org.quarkpress.posts.runtime.PostsConfig;

class PostsProcessor {

    static final String FEATURE = "quarkpress-posts";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("posts", 4);
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("quarkpress.posts", PostsConfig.class);
    }

    @BuildStep
    List<SiteScopedTableBuildItem> siteScopedTables() {
        return List.of(new SiteScopedTableBuildItem("qp_post"), new SiteScopedTableBuildItem("qp_post_lock"));
    }
}

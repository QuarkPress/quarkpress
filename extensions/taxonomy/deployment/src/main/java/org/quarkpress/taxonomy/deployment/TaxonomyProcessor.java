package org.quarkpress.taxonomy.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import java.util.List;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.SiteScopedTableBuildItem;

class TaxonomyProcessor {

    static final String FEATURE = "quarkpress-taxonomy";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MigrationLocationBuildItem migrations() {
        return new MigrationLocationBuildItem("taxonomy", 5);
    }

    @BuildStep
    List<SiteScopedTableBuildItem> siteScopedTables() {
        return List.of(new SiteScopedTableBuildItem("qp_term"), new SiteScopedTableBuildItem("qp_term_assignment"));
    }
}

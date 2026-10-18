package org.quarkpress.multisite.deployment;

import io.quarkus.builder.BuildChainBuilder;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

/**
 * Declares features from a test, the way an extension's deployment module would. Same as the persistence deployment
 * test helper of that name.
 */
public final class TestFeatures {

    private TestFeatures() {}

    public static void declare(BuildChainBuilder chain, SiteFeatureBuildItem... features) {
        chain.addBuildStep(context -> {
                    for (SiteFeatureBuildItem feature : features) {
                        context.produce(feature);
                    }
                })
                .produces(SiteFeatureBuildItem.class)
                .build();
    }
}

package org.quarkpress.outbound.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import org.jboss.jandex.DotName;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.outbound.runtime.OutboundConfig;
import org.quarkpress.outbound.runtime.OutboundStartup;

class OutboundProcessor {

    static final String FEATURE = "quarkpress-outbound";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("outbound", "quarkpress.outbound", OutboundConfig.class);
    }

    /** The start-up announcement of lifted address checks. */
    @BuildStep
    AdditionalBeanBuildItem startup() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(OutboundStartup.class)
                .setDefaultScope(DotName.createSimple(Singleton.class))
                .setUnremovable()
                .build();
    }
}

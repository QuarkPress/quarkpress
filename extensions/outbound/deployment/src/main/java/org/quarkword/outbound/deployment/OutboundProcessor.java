package org.quarkword.outbound.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import org.jboss.jandex.DotName;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.outbound.runtime.OutboundConfig;
import org.quarkword.outbound.runtime.OutboundStartup;

class OutboundProcessor {

    static final String FEATURE = "quarkword-outbound";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("outbound", "quarkword.outbound", OutboundConfig.class);
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

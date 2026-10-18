package org.quarkpress.core.deployment;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.core.CapabilityCheck;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkpress.core.runtime.CoreRecorder;
import org.quarkpress.core.runtime.FeatureResolver;
import org.quarkpress.core.runtime.RequestFeatureCache;
import org.quarkpress.core.runtime.RequestOptionCache;
import org.quarkpress.core.runtime.SiteBinding;
import org.quarkpress.core.runtime.SiteResolution;

class CoreProcessor {

    static final String FEATURE = "quarkpress-core";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovable() {
        return UnremovableBeanBuildItem.beanTypes(
                SiteBinding.class,
                SiteResolution.class,
                SiteContext.class,
                RequestOptionCache.class,
                RequestFeatureCache.class,
                FeatureResolver.class,
                QuarkPressClock.class,
                CapabilityCheck.class);
    }

    @BuildStep
    @Record(ExecutionTime.STATIC_INIT)
    SyntheticBeanBuildItem featureRegistry(CoreRecorder recorder, List<SiteFeatureBuildItem> features) {
        List<FeatureDescriptor> descriptors = new ArrayList<>();
        for (SiteFeatureBuildItem feature : features) {
            descriptors.add(feature.descriptor());
        }
        FeatureRegistry validated;
        try {
            validated = new FeatureRegistry(descriptors);
        } catch (IllegalArgumentException duplicate) {
            throw new IllegalStateException(duplicate.getMessage(), duplicate);
        }
        List<String> keys = new ArrayList<>();
        List<String> modules = new ArrayList<>();
        List<Boolean> defaults = new ArrayList<>();
        List<String> descriptions = new ArrayList<>();
        for (FeatureDescriptor feature : validated.all()) {
            keys.add(feature.key());
            modules.add(feature.module());
            defaults.add(feature.defaultEnabled());
            descriptions.add(feature.description());
        }
        return SyntheticBeanBuildItem.configure(FeatureRegistry.class)
                .scope(Singleton.class)
                .unremovable()
                .supplier(recorder.featureRegistry(keys, modules, defaults, descriptions))
                .done();
    }
}

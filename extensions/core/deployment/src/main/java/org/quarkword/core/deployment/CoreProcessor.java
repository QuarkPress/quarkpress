package org.quarkword.core.deployment;

import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem;
import io.quarkus.arc.deployment.ValidationPhaseBuildItem.ValidationErrorBuildItem;
import io.quarkus.arc.processor.BeanInfo;
import io.quarkus.arc.processor.BuildExtension;
import io.quarkus.arc.processor.DecoratorInfo;
import io.quarkus.arc.processor.InterceptorInfo;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jboss.jandex.Type;
import org.quarkword.core.CapabilityCheck;
import org.quarkword.core.FeatureDescriptor;
import org.quarkword.core.FeatureRegistry;
import org.quarkword.core.QuarkWordClock;
import org.quarkword.core.SiteContext;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.core.deployment.spi.SiteFeatureBuildItem;
import org.quarkword.core.runtime.CoreRecorder;
import org.quarkword.core.runtime.FeatureResolver;
import org.quarkword.core.runtime.NoPresetSite;
import org.quarkword.core.runtime.PresetSite;
import org.quarkword.core.runtime.RequestFeatureCache;
import org.quarkword.core.runtime.RequestOptionCache;
import org.quarkword.core.runtime.SiteBinding;
import org.quarkword.core.runtime.SiteResolution;

class CoreProcessor {

    static final String FEATURE = "quarkword-core";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** Fails the start on a {@code quarkword.*} setting no module declared. */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void checkConfigKeys(CoreRecorder recorder, List<QuarkWordConfigKeysBuildItem> declarations) {
        Set<String> keys = new java.util.TreeSet<>();
        for (QuarkWordConfigKeysBuildItem declaration : declarations) {
            keys.addAll(declaration.keys());
        }
        recorder.checkConfigKeys(new ArrayList<>(keys));
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
                QuarkWordClock.class,
                CapabilityCheck.class);
    }

    /** The package whose beans may decide a request's site before QuarkWord code runs. */
    static final String PRESET_PACKAGE = "org.quarkword.multisite.runtime.";

    /**
     * A preset binding bypasses the resolvers and the scope checks, so only core's empty default and the multisite
     * module's HTTP layer may provide one.
     */
    @BuildStep
    void presetSites(ValidationPhaseBuildItem validation, BuildProducer<ValidationErrorBuildItem> errors) {
        for (BeanInfo bean : validation.getContext().beans().withBeanType(PresetSite.class)) {
            refuseUnlessTrusted(bean, errors);
            for (InterceptorInfo interceptor : bean.getBoundInterceptors()) {
                String name = interceptor.getBeanClass().toString();
                if (!name.startsWith(PRESET_PACKAGE)) {
                    errors.produce(new ValidationErrorBuildItem(new IllegalStateException("QuarkWord: " + name
                            + " intercepts the PresetSite bean " + bean.getBeanClass()
                            + "; only the multisite module decides a request's site before QuarkWord code runs")));
                }
            }
        }
        for (DecoratorInfo decorator : validation.getContext().get(BuildExtension.Key.DECORATORS)) {
            if (hasType(decorator.getDecoratedTypes()) || hasType(decorator.getTypes())) {
                refuse(decorator, errors);
            }
        }
    }

    private static boolean hasType(Set<Type> types) {
        for (Type type : types) {
            if (type.name().toString().equals(PresetSite.class.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void refuseUnlessTrusted(BeanInfo bean, BuildProducer<ValidationErrorBuildItem> errors) {
        String type = bean.getBeanClass().toString();
        if (!type.equals(NoPresetSite.class.getName()) && !type.startsWith(PRESET_PACKAGE)) {
            refuse(bean, errors);
        }
    }

    private static void refuse(BeanInfo bean, BuildProducer<ValidationErrorBuildItem> errors) {
        errors.produce(new ValidationErrorBuildItem(new IllegalStateException("QuarkWord: " + bean.getBeanClass()
                + " implements PresetSite; only the multisite module decides a request's site before"
                + " QuarkWord code runs")));
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

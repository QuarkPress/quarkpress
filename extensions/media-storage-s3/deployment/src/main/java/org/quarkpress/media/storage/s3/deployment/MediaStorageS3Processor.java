package org.quarkpress.media.storage.s3.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import org.jboss.jandex.DotName;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.media.deployment.MediaStorageBuildItem;
import org.quarkpress.media.runtime.MediaBuildTimeConfig;
import org.quarkpress.media.storage.s3.runtime.S3Config;
import org.quarkpress.media.storage.s3.runtime.S3Startup;

class MediaStorageS3Processor {

    static final String FEATURE = "quarkpress-media-storage-s3";

    /** The backend's name, as {@code quarkpress.media.storage} names it. */
    static final String BACKEND = "s3";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    MediaStorageBuildItem backend() {
        return new MediaStorageBuildItem(BACKEND);
    }

    @BuildStep
    QuarkPressConfigKeysBuildItem configKeys() {
        return QuarkPressConfigKeysBuildItem.of("media-storage-s3", "quarkpress.media.s3", S3Config.class);
    }

    /**
     * The start-up check of the settings, only when this backend is the one in use: {@code quarkpress.media.storage}
     * names it, or names none (then the build succeeds only when it is the only backend).
     */
    @BuildStep
    void activeBackendBeans(
            MediaBuildTimeConfig config,
            BuildProducer<AdditionalBeanBuildItem> beans,
            BuildProducer<ExcludedTypeBuildItem> excluded) {
        if (active(config)) {
            beans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClasses(S3Startup.class)
                    .setDefaultScope(DotName.createSimple(Singleton.class))
                    .setUnremovable()
                    .build());
        } else {
            // its observer method would make it a bean of its own accord
            excluded.produce(new ExcludedTypeBuildItem(S3Startup.class.getName()));
        }
    }

    /** Whether the build uses this backend: the media module's choice, made from the same setting. */
    static boolean active(MediaBuildTimeConfig config) {
        return config.storage().map(BACKEND::equals).orElse(true);
    }
}

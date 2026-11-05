package org.quarkpress.media.storage.fs.deployment;

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
import org.quarkpress.media.storage.fs.runtime.FsConfig;
import org.quarkpress.media.storage.fs.runtime.FsStartup;
import org.quarkpress.media.storage.fs.runtime.UploadsRoute;

class MediaStorageFsProcessor {

    static final String FEATURE = "quarkpress-media-storage-fs";

    /** The backend's name, as {@code quarkpress.media.storage} names it. */
    static final String BACKEND = "fs";

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
        return QuarkPressConfigKeysBuildItem.of("media-storage-fs", "quarkpress.media.fs", FsConfig.class);
    }

    /**
     * The start-up check of the directory and the route under the uploads path, only when this backend is the one in
     * use: {@code quarkpress.media.storage} names it, or names none (then the build succeeds only when it is the only
     * backend). The route itself stands back when a base URL is configured.
     */
    @BuildStep
    void activeBackendBeans(
            MediaBuildTimeConfig config,
            BuildProducer<AdditionalBeanBuildItem> beans,
            BuildProducer<ExcludedTypeBuildItem> excluded) {
        if (active(config)) {
            beans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClasses(FsStartup.class, UploadsRoute.class)
                    .setDefaultScope(DotName.createSimple(Singleton.class))
                    .setUnremovable()
                    .build());
        } else {
            // their observer methods would make them beans of their own accord
            excluded.produce(new ExcludedTypeBuildItem(FsStartup.class.getName()));
            excluded.produce(new ExcludedTypeBuildItem(UploadsRoute.class.getName()));
        }
    }

    /** Whether the build uses this backend: the media module's choice, made from the same setting. */
    static boolean active(MediaBuildTimeConfig config) {
        return config.storage().map(BACKEND::equals).orElse(true);
    }
}

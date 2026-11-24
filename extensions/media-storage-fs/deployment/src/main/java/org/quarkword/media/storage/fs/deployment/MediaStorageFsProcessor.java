package org.quarkword.media.storage.fs.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.ExcludedTypeBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import jakarta.inject.Singleton;
import org.jboss.jandex.DotName;
import org.quarkword.core.deployment.spi.QuarkWordConfigKeysBuildItem;
import org.quarkword.media.deployment.MediaStorageBuildItem;
import org.quarkword.media.runtime.MediaBuildTimeConfig;
import org.quarkword.media.storage.fs.runtime.FsConfig;
import org.quarkword.media.storage.fs.runtime.FsStartup;
import org.quarkword.media.storage.fs.runtime.UploadsRoute;

class MediaStorageFsProcessor {

    static final String FEATURE = "quarkword-media-storage-fs";

    /** The backend's name, as {@code quarkword.media.storage} names it. */
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
    QuarkWordConfigKeysBuildItem configKeys() {
        return QuarkWordConfigKeysBuildItem.of("media-storage-fs", "quarkword.media.fs", FsConfig.class);
    }

    /**
     * The start-up check of the directory and the route under the uploads path, only when this backend is the one in
     * use: {@code quarkword.media.storage} names it, or names none (then the build succeeds only when it is the only
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

package org.quarkpress.media.storage.fs.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.media.deployment.MediaStorageBuildItem;
import org.quarkpress.media.storage.fs.runtime.FsConfig;

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
}

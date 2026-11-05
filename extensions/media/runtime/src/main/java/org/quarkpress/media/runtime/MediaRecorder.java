package org.quarkpress.media.runtime;

import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.runtime.annotations.Recorder;

/** Hands the build's decisions to the media beans at start-up. */
@Recorder
public class MediaRecorder {

    /** Tells {@link ActiveStorage} which backend the build chose: its name and the id of its bean. */
    public void chooseStorage(BeanContainer container, String name, String beanId) {
        container.beanInstance(ActiveStorage.class).choose(name, beanId);
    }
}

package org.quarkword.multisite.runtime;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class MultisiteRecorder {

    public void nonApplicationRoot(String path) {
        NonApplicationPath.install(path);
    }
}

package org.quarkpress.restwp.runtime;

import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class RestWpRecorder {

    public void nonApplicationRoot(String path) {
        RestPaths.installNonApplicationRoot(path);
    }

    public void entryPath(String path) {
        RestPaths.installEntryPath(path);
    }
}

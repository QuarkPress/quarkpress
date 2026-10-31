package org.quarkpress.restwp.runtime;

import io.quarkus.runtime.annotations.Recorder;
import org.quarkpress.restwp.runtime.wpv2.BuiltInSettings;

@Recorder
public class RestWpRecorder {

    public void nonApplicationRoot(String path) {
        RestPaths.installNonApplicationRoot(path);
    }

    public void entryPath(String path) {
        RestPaths.installEntryPath(path);
    }

    public void multisite(boolean enabled) {
        BuiltInSettings.installMultisite(enabled);
    }
}

package org.quarkpress.persistence.runtime;

import io.quarkus.runtime.annotations.Recorder;
import java.util.List;
import java.util.Map;

@Recorder
public class PersistenceRecorder {

    public void siteAssociations(Map<String, List<String>> fieldsByClass) {
        SiteAssociations.install(fieldsByClass);
    }
}

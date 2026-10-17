package org.quarkpress.core.runtime;

import io.quarkus.runtime.annotations.Recorder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.FeatureRegistry;

@Recorder
public class CoreRecorder {

    public Supplier<FeatureRegistry> featureRegistry(
            List<String> keys, List<String> modules, List<Boolean> defaults, List<String> descriptions) {
        List<FeatureDescriptor> features = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            features.add(new FeatureDescriptor(keys.get(i), modules.get(i), defaults.get(i), descriptions.get(i)));
        }
        FeatureRegistry registry = new FeatureRegistry(features);
        return () -> registry;
    }
}

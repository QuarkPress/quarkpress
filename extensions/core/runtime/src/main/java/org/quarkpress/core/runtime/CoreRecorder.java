package org.quarkpress.core.runtime;

import io.quarkus.runtime.annotations.Recorder;
import io.smallrye.config.EnvConfigSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.ConfigProvider;
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

    /**
     * Fails the start when a {@code quarkpress.*} property, from any source except environment variables (whose names
     * are ambiguous: {@code QUARKPRESS_A_B} could be {@code a.b} or {@code a-b}), matches none of the declared keys. A
     * declared {@code name[]} accepts {@code name} and {@code name[n]}; a declared {@code name.*} accepts exactly one
     * further segment, plain ({@code name.a}, without {@code [}, {@code ]} or {@code *}) or quoted
     * ({@code name."a.b"}).
     */
    public void checkConfigKeys(List<String> declared) {
        java.util.Set<String> exact = new java.util.HashSet<>();
        java.util.Set<String> lists = new java.util.HashSet<>();
        java.util.Set<String> maps = new java.util.HashSet<>();
        for (String key : declared) {
            if (key.endsWith("[]")) {
                lists.add(key.substring(0, key.length() - 2));
            } else if (key.endsWith(".*")) {
                maps.add(key.substring(0, key.length() - 1));
            } else {
                exact.add(key);
            }
        }
        java.util.Set<String> unknown = new java.util.TreeSet<>();
        for (var source : ConfigProvider.getConfig().getConfigSources()) {
            if (source instanceof EnvConfigSource) {
                continue;
            }
            for (String name : source.getPropertyNames()) {
                String key = name;
                if (key.startsWith("%")) {
                    int dot = key.indexOf('.');
                    key = dot < 0 ? key : key.substring(dot + 1);
                }
                if (key.startsWith("quarkpress.") && !isDeclared(key, exact, lists, maps)) {
                    unknown.add(key);
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("Unknown QuarkPress setting" + (unknown.size() > 1 ? "s " : " ")
                    + String.join(", ", unknown) + ": the quarkpress.* namespace is reserved for QuarkPress and no"
                    + " installed module declares " + (unknown.size() > 1 ? "them" : "it") + ". Declared settings: "
                    + new java.util.TreeSet<>(declared));
        }
    }

    private static boolean isDeclared(
            String key, java.util.Set<String> exact, java.util.Set<String> lists, java.util.Set<String> maps) {
        if (exact.contains(key) || lists.contains(key)) {
            return true;
        }
        for (String map : maps) {
            if (key.startsWith(map) && isOneSegment(key, map.length())) {
                return true;
            }
        }
        if (key.endsWith("]")) {
            int open = key.lastIndexOf('[');
            if (open > 0 && lists.contains(key.substring(0, open))) {
                String index = key.substring(open + 1, key.length() - 1);
                return !index.isEmpty() && index.chars().allMatch(Character::isDigit);
            }
        }
        return false;
    }

    /**
     * Whether {@code key} from {@code start} on is exactly one map key segment: a non-empty plain name without dots,
     * quotes, brackets or {@code *} (so it never looks like a list index or a declared map), or a non-empty quoted name
     * (which may hold any of these but a quote) closed by the key's last character.
     */
    private static boolean isOneSegment(String key, int start) {
        int end = key.length();
        if (start >= end) {
            return false;
        }
        if (key.charAt(start) == '"') {
            if (end - start < 3 || key.charAt(end - 1) != '"') {
                return false;
            }
            return key.indexOf('"', start + 1) == end - 1;
        }
        for (int i = start; i < end; i++) {
            char c = key.charAt(i);
            if (c == '.' || c == '"' || c == '[' || c == ']' || c == '*') {
                return false;
            }
        }
        return true;
    }
}

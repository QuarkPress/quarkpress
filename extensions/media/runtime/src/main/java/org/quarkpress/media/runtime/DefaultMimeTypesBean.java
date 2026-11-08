package org.quarkpress.media.runtime;

import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.quarkpress.media.MimeTypes;

/**
 * The upload types of every site unless an application bean replaces this one: WordPress's list followed by the
 * types {@code quarkpress.media.allowed-types} adds. A configured pattern WordPress's list already holds changes that
 * pattern's type where it stands. A configured pattern holding a dot, or a type longer than 100 characters, fails the
 * start.
 */
@DefaultBean
@ApplicationScoped
public class DefaultMimeTypesBean implements MimeTypes {

    /** The longest type the media table's type column holds. */
    static final int MAX_TYPE_LENGTH = 100;

    @Inject
    MediaConfig config;

    private volatile List<Map.Entry<String, String>> patterns;

    /** Fails the start when a configured pattern could never match (see {@link #refusal}). */
    void checkConfiguredTypes(@Observes StartupEvent start) {
        String refusal = refusal(config.allowedTypes());
        if (!refusal.isEmpty()) {
            throw new IllegalStateException(refusal);
        }
    }

    @Override
    public List<Map.Entry<String, String>> patterns() {
        List<Map.Entry<String, String>> current = patterns;
        if (current == null) {
            // The configuration hands its map over without an order of its own: sorting the patterns keeps the
            // lookup order the same from one start to the next.
            current = merge(DefaultMimeTypes.PATTERNS, new TreeMap<>(config.allowedTypes()));
            patterns = current;
        }
        return current;
    }

    /**
     * {@code base} with {@code added} applied in its iteration order: a pattern {@code base} holds gets the added
     * type in place, any other pattern comes after the list.
     */
    static List<Map.Entry<String, String>> merge(List<Map.Entry<String, String>> base, Map<String, String> added) {
        List<Map.Entry<String, String>> merged = new ArrayList<>(base);
        for (Map.Entry<String, String> entry : added.entrySet()) {
            Map.Entry<String, String> pattern = Map.entry(entry.getKey(), entry.getValue());
            int at = indexOf(merged, entry.getKey());
            if (at >= 0) {
                merged.set(at, pattern);
            } else {
                merged.add(pattern);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Why the configured upload types cannot be used, empty when they can: a pattern holding a dot could never match,
     * since a file's type is looked up by the part of its name after its last dot, and a type longer than 100
     * characters could never be stored.
     */
    static String refusal(Map<String, String> configured) {
        StringBuilder refusal = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(configured).entrySet()) {
            String pattern = entry.getKey();
            if (pattern.indexOf('.') >= 0) {
                separate(refusal)
                        .append("quarkpress.media.allowed-types.\"")
                        .append(pattern)
                        .append(
                                "\" cannot be used: an upload type's pattern holds no dot, as a file's type is looked up"
                                        + " by the part of the file name after its last dot");
            }
            String type = entry.getValue();
            if (type != null && type.length() > MAX_TYPE_LENGTH) {
                separate(refusal)
                        .append("quarkpress.media.allowed-types.\"")
                        .append(pattern)
                        .append("\" cannot be used: its type ")
                        .append(type)
                        .append(" is longer than the ")
                        .append(MAX_TYPE_LENGTH)
                        .append(" characters an attachment's type holds");
            }
        }
        return refusal.toString();
    }

    private static StringBuilder separate(StringBuilder refusal) {
        return refusal.isEmpty() ? refusal : refusal.append("; ");
    }

    private static int indexOf(List<Map.Entry<String, String>> patterns, String key) {
        for (int i = 0; i < patterns.size(); i++) {
            if (patterns.get(i).getKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }
}

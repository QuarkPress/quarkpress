package org.quarkpress.media.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WordPress's list of upload types: 98 extension patterns (such as {@code jpg|jpeg|jpe}), each with its MIME type, in
 * WordPress's lookup order. The list is read once from {@code mime-types.tsv} next to this class, one
 * {@code pattern<TAB>mime} line per type.
 */
public final class DefaultMimeTypes {

    /** The resource holding the list. */
    public static final String RESOURCE = "org/quarkpress/media/runtime/mime-types.tsv";

    /** The pattern and MIME type of each of WordPress's upload types, in lookup order; unmodifiable. */
    public static final List<Map.Entry<String, String>> PATTERNS = load();

    private static final Set<String> FULL_EXTENSIONS = collectExtensions(PATTERNS, Set.of());

    private static final List<String> INSTALLER_EXTENSIONS =
            List.copyOf(collectExtensions(PATTERNS, Set.of("swf", "exe")));

    private DefaultMimeTypes() {}

    /** Every extension of the list, in lower case, in list order. */
    public static Set<String> fullExtensions() {
        return FULL_EXTENSIONS;
    }

    /**
     * The extension a file of {@code mime} gets by default: the first extension of the first pattern of that type
     * ({@code image/jpeg} gives {@code jpg}); null when the list has no such type.
     */
    public static String defaultExtension(String mime) {
        for (Map.Entry<String, String> entry : PATTERNS) {
            if (entry.getValue().equals(mime)) {
                return AllowedTypes.extensionsOf(entry.getKey()).get(0);
            }
        }
        return null;
    }

    /**
     * The upload types a network starts with, as WordPress's installer writes them: every extension of the list
     * except {@code swf} and {@code exe}, each once, in list order.
     */
    public static List<String> installerExtensions() {
        return INSTALLER_EXTENSIONS;
    }

    private static Set<String> collectExtensions(List<Map.Entry<String, String>> patterns, Set<String> left) {
        LinkedHashSet<String> extensions = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : patterns) {
            for (String extension : AllowedTypes.extensionsOf(entry.getKey())) {
                String lower = extension.toLowerCase(Locale.ROOT);
                if (!left.contains(lower)) {
                    extensions.add(lower);
                }
            }
        }
        return Collections.unmodifiableSet(extensions);
    }

    private static List<Map.Entry<String, String>> load() {
        InputStream in = DefaultMimeTypes.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("The upload type list " + RESOURCE + " is missing");
        }
        List<Map.Entry<String, String>> patterns = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0 || tab == line.length() - 1 || line.indexOf('\t', tab + 1) >= 0) {
                    throw new IllegalStateException("Malformed line in " + RESOURCE + ": " + line);
                }
                patterns.add(Map.entry(line.substring(0, tab), line.substring(tab + 1)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(patterns);
    }
}

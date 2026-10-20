package org.quarkpress.testing.wpscenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A directory of hand-written scenarios: its {@code index.txt} names one scenario file per line, in order; blank lines
 * and lines starting with {@code #} are skipped.
 */
final class ScenarioIndex {

    private ScenarioIndex() {}

    /** The scenarios the index of {@code directory} (a resource path ending in a slash) lists, in that order. */
    static List<Scenario> load(String directory) {
        List<Scenario> scenarios = new ArrayList<>();
        for (String file : entries(resource(directory + "index.txt"))) {
            scenarios.add(ScenarioJson.parse(resource(directory + file)));
        }
        return List.copyOf(scenarios);
    }

    /** The file names of an index text: each line stripped, without blank lines and comments. */
    static List<String> entries(String index) {
        return index.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static String resource(String name) {
        try (InputStream in = ScenarioIndex.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing scenario resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

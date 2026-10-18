package org.quarkpress.testing.wpscenario;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The multisite scenarios: hand-written ones per behaviour-reference rule. */
public final class MultisiteScenarios {

    static final String DIRECTORY = "scenarios/multisite/";

    private MultisiteScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/multisite/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        List<Scenario> scenarios = new ArrayList<>();
        for (String line : resource(DIRECTORY + "index.txt").split("\n")) {
            String file = line.strip();
            if (file.isEmpty() || file.startsWith("#")) {
                continue;
            }
            scenarios.add(ScenarioJson.parse(resource(DIRECTORY + file)));
        }
        return List.copyOf(scenarios);
    }

    /** Every scenario to compare. */
    public static List<Scenario> all() {
        return handWritten();
    }

    private static String resource(String name) {
        try (InputStream in = MultisiteScenarios.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing scenario resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

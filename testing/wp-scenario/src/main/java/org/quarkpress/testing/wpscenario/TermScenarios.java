package org.quarkpress.testing.wpscenario;

import java.util.ArrayList;
import java.util.List;

/**
 * The term scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference rule (and,
 * from the generated-scenarios task on, generated ones).
 */
public final class TermScenarios {

    static final String DIRECTORY = "scenarios/terms/";

    private TermScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/terms/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    public static List<Scenario> all() {
        return List.copyOf(new ArrayList<>(handWritten()));
    }
}

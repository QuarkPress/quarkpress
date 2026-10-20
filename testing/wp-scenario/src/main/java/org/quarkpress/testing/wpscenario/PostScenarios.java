package org.quarkpress.testing.wpscenario;

import java.util.List;

/** The post scenarios: hand-written ones per behaviour-reference rule, all on the subdirectory network's main site. */
public final class PostScenarios {

    static final String DIRECTORY = "scenarios/posts/";

    private PostScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/posts/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** Every post scenario. */
    public static List<Scenario> all() {
        return handWritten();
    }
}

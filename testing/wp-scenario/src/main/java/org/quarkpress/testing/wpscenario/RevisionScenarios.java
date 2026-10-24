package org.quarkpress.testing.wpscenario;

import java.util.List;

/**
 * The revision scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference rule.
 */
public final class RevisionScenarios {

    static final String DIRECTORY = "scenarios/revisions/";

    private RevisionScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/revisions/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** Every revision scenario (the hand-written ones, until generated ones are added). */
    public static List<Scenario> all() {
        return handWritten();
    }
}

package org.quarkpress.testing.wpscenario;

import java.util.List;

/**
 * The scheduling scenarios, all on the subdirectory network's main site: hand-written ones per behaviour-reference
 * rule (the publication check of a scheduled post, what a cron publication changes, the trash purge and the
 * auto-draft purge). Generated ones are drawn with test-gen from fixed seeds once the generator exists; until then
 * {@link #all} holds the hand-written scenarios only.
 */
public final class SchedulingScenarios {

    static final String DIRECTORY = "scenarios/scheduling/";

    /** Generated scenarios per seed. */
    public static final int GENERATED = 20;

    private SchedulingScenarios() {}

    /** The hand-written scenarios listed in {@code scenarios/scheduling/index.txt}, in that order. */
    public static List<Scenario> handWritten() {
        return ScenarioIndex.load(DIRECTORY);
    }

    /** The hand-written scenarios (the generated ones follow once the generator exists). */
    public static List<Scenario> all() {
        return List.copyOf(handWritten());
    }
}

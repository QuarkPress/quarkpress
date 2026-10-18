package org.quarkpress.testing.wpscenario;

import java.util.LinkedHashSet;
import java.util.Set;
import org.quarkpress.testing.wporacle.ParityRun;

/** Compares two outcomes aspect by aspect; an aspect only one side recorded compares against {@link #ABSENT}. */
public final class ScenarioComparison {

    public static final String ABSENT = "(absent)";

    private ScenarioComparison() {}

    public static void compare(ParityRun run, String caseId, Outcome wordpress, Outcome quarkpress) {
        Set<String> aspects = new LinkedHashSet<>(wordpress.facts().keySet());
        aspects.addAll(quarkpress.facts().keySet());
        for (String aspect : aspects) {
            run.compare(
                    caseId,
                    aspect,
                    wordpress.facts().getOrDefault(aspect, ABSENT),
                    quarkpress.facts().getOrDefault(aspect, ABSENT));
        }
    }
}

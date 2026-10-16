package org.quarkpress.testing.wporacle;

import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.sessions.JqwikSession;

/** Draws inputs from a module's jqwik arbitraries with a fixed seed, so a parity batch is the same on every run. */
public final class GeneratedCases {

    private GeneratedCases() {}

    public static <T> List<T> sample(Arbitrary<T> arbitrary, String seed, int count) {
        JqwikSession.start(seed);
        try {
            return arbitrary.sampleStream().limit(count).toList();
        } finally {
            JqwikSession.finish();
        }
    }
}
